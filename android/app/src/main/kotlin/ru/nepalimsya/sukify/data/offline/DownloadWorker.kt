package ru.nepalimsya.sukify.data.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.R
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.model.TransitionDto

/**
 * Загрузчик треков (WorkManager, foreground dataSync): три потока прямо из
 * публичного S3, докачка недокачанного по Range, проверка размера и md5
 * (это и есть audio_rev), атомарная подмена файла. Пока новая версия не
 * готова, играет старая. После аудио — обложки и тексты, чтобы без сети
 * экраны и «Текст» выглядели так же, как с сетью.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloads: DownloadDao,
    private val dao: OfflineCatalogDao,
    private val lyricsDao: OfflineLyricsDao,
    private val catalog: OfflineCatalog,
    private val storage: OfflineStorage,
    private val rules: DownloadRules,
    private val http: OkHttpClient,
    private val api: SukifyApi,
    private val transitions: OfflineTransitionDao,
    private val json: Json,
) : CoroutineWorker(context, params) {

    private enum class Outcome { Done, Network, Failed }

    private val claimed = HashSet<Long>()
    private val claimLock = Mutex()
    private val completed = AtomicInteger(0)
    private val networkDown = AtomicBoolean(false)

    override suspend fun doWork(): Result {
        val urls = catalog.urls() ?: return Result.success()
        downloads.resetInterrupted()
        val total = downloads.pendingCount(MAX_ATTEMPTS)
        if (total > 0) {
            runCatching { setForeground(foregroundInfo(0, total)) }
            coroutineScope {
                repeat(PARALLEL) {
                    launch(Dispatchers.IO) {
                        while (!isStopped && !networkDown.get()) {
                            val row = claimNext() ?: break
                            try {
                                if (download(row, urls) == Outcome.Network) networkDown.set(true)
                            } finally {
                                claimLock.withLock { claimed -= row.trackId }
                            }
                            val left = downloads.pendingCount(MAX_ATTEMPTS)
                            runCatching { setForeground(foregroundInfo(completed.get(), completed.get() + left)) }
                        }
                    }
                }
            }
        }
        if (networkDown.get() || isStopped) return Result.retry()
        prefetchCovers(urls)
        prefetchLyrics()
        prefetchTransitions()
        return Result.success()
    }

    /** Следующий трек, который ещё никто из потоков не взял. */
    private suspend fun claimNext(): DownloadEntity? = claimLock.withLock {
        downloads.pending(limit = PARALLEL * 4, maxAttempts = MAX_ATTEMPTS)
            .firstOrNull { it.trackId !in claimed }
            ?.also { claimed += it.trackId }
    }

    private suspend fun download(row: DownloadEntity, urls: ManifestUrls): Outcome = withContext(Dispatchers.IO) {
        val volume = rules.current().volume
        val target = storage.audioFile(volume, row.trackId, row.rev)
        val part = storage.partFile(volume, row.trackId, row.rev)

        // Процесс убили сразу после переименования — файл уже готов.
        if (target.exists() && target.length() == row.size && md5(target) == row.rev) {
            finish(row, target)
            return@withContext Outcome.Done
        }

        var have = if (part.exists()) part.length() else 0L
        if (have >= row.size) {
            part.delete()
            have = 0
        }
        downloads.progress(row.trackId, have)

        val request = Request.Builder()
            .url("${urls.audio(row.trackId)}?v=${row.rev}")
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        val response = try {
            runInterruptible { http.newCall(request).execute() }
        } catch (e: IOException) {
            debug("network error on ${row.trackId}: ${e.message}")
            return@withContext Outcome.Network
        }

        response.use { resp ->
            when {
                resp.code == 404 || resp.code == 403 || resp.code == 410 -> {
                    downloads.failed(row.trackId, "Файла нет на сервере")
                    return@withContext Outcome.Failed
                }
                resp.code == 416 -> {
                    part.delete()
                    downloads.failed(row.trackId, "Не удалось докачать — начну заново")
                    return@withContext Outcome.Failed
                }
                !resp.isSuccessful -> {
                    downloads.failed(row.trackId, "Сервер ответил ${resp.code}")
                    return@withContext Outcome.Failed
                }
            }
            // Сервер мог проигнорировать Range и отдать файл целиком.
            val append = resp.code == 206 && have > 0
            if (!append) have = 0
            try {
                FileOutputStream(part, append).use { out ->
                    val input = resp.body.byteStream()
                    val buffer = ByteArray(BUFFER)
                    var written = have
                    var reported = System.currentTimeMillis()
                    while (true) {
                        if (isStopped) return@withContext Outcome.Network
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - reported >= PROGRESS_MS) {
                            downloads.progress(row.trackId, written)
                            reported = now
                        }
                    }
                }
            } catch (e: IOException) {
                debug("interrupted ${row.trackId}: ${e.message}")
                return@withContext Outcome.Network
            }
        }

        if (part.length() != row.size) {
            part.delete()
            downloads.failed(row.trackId, "Файл скачался не целиком")
            return@withContext Outcome.Failed
        }
        if (md5(part) != row.rev) {
            // Файл на сервере успели заменить — следующая сверка даст новую ревизию.
            part.delete()
            downloads.failed(row.trackId, "Файл повреждён при загрузке")
            return@withContext Outcome.Failed
        }
        if (!part.renameTo(target)) {
            downloads.failed(row.trackId, "Не удалось сохранить файл")
            return@withContext Outcome.Failed
        }
        finish(row, target)
        Outcome.Done
    }

    private suspend fun finish(row: DownloadEntity, target: File) {
        val updated = downloads.done(row.trackId, row.rev, target.path)
        if (updated == 0) {
            // Пока качали, трек убрали из загрузок или файл снова заменили.
            target.delete()
            return
        }
        completed.incrementAndGet()
        // Новая ревизия на месте — старая больше не нужна.
        row.path?.takeIf { it != target.path }?.let { File(it).delete() }
    }

    /** Обложки скачанного: 160 px для списков и 640 px для шапок и «Сейчас играет». */
    private suspend fun prefetchCovers(urls: ManifestUrls) = withContext(Dispatchers.IO) {
        val volume = rules.current().volume
        val done = downloads.all().filter { it.state == DownloadState.DONE }.map { it.trackId }
        if (done.isEmpty()) {
            File(storage.coverDir(volume).path).listFiles()?.forEach { it.delete() }
            storage.refreshCoverIndex()
            return@withContext
        }
        val tracks = dao.tracksByIds(done)
        val releases = dao.releasesByIds(tracks.mapNotNull { it.releaseId }.distinct())
        val wanted = HashMap<File, String>()
        for (release in releases) {
            val v = release.coverV ?: continue
            for (size in COVER_SIZES) wanted[storage.coverTarget(volume, 'r', release.id, size, v)] = urls.releaseCover(release.id, size, v)
        }
        for (track in tracks.filter { it.ownCover }) {
            for (size in COVER_SIZES) wanted[storage.coverTarget(volume, 't', track.id, size, null)] = urls.trackCover(track.id, size)
        }
        for ((file, url) in wanted) {
            if (isStopped) break
            if (file.exists()) continue
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val tmp = File(file.path + ".part")
                    tmp.outputStream().use { resp.body.byteStream().copyTo(it) }
                    tmp.renameTo(file)
                }
            }
        }
        // Обложки того, что больше не скачано, — убрать.
        storage.coverDir(volume).listFiles()?.forEach { f -> if (f !in wanted.keys) f.delete() }
        storage.refreshCoverIndex()
    }

    /** Тексты скачанных треков — по 50 за раз, чтобы не нагружать сервер. */
    private suspend fun prefetchLyrics() {
        val have = lyricsDao.ids().toSet()
        val missing = downloads.all().filter { it.state == DownloadState.DONE && it.trackId !in have }.take(50)
        for (row in missing) {
            if (isStopped) break
            val lyrics = apiCall { api.lyrics(row.trackId) }.getOrNull() ?: continue
            lyricsDao.put(OfflineLyricsEntity(row.trackId, lyrics.synced, lyrics.plain))
        }
    }

    /**
     * Переходы соседних пар скачанного (личный выбор или лучший у сообщества) —
     * чтобы микс и без сети звучал так же. Пары без своего перехода тоже
     * запоминаем: значит, играть глобальный кроссфейд.
     */
    private suspend fun prefetchTransitions() {
        val done = downloads.all().filter { it.state == DownloadState.DONE }.mapTo(HashSet()) { it.trackId }
        if (done.size < 2) return
        val pairs = LinkedHashSet<String>()
        for (rows in dao.allSourceTracks().groupBy { it.sourceKey }.values) {
            rows.sortedBy { it.position }.zipWithNext().forEach { (a, b) ->
                if (a.trackId in done && b.trackId in done) pairs += "${a.trackId}:${b.trackId}"
            }
        }
        for (chunk in pairs.chunked(200)) {
            if (isStopped) break
            val result = apiCall { api.transitionsForContext(chunk.joinToString(",")).data }.getOrNull() ?: break
            transitions.put(
                chunk.map { key ->
                    val (from, to) = key.split(':').map { it.toLong() }
                    OfflineTransitionEntity(from, to, result[key]?.let { json.encodeToString(TransitionDto.serializer(), it) })
                },
            )
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, downloads.pendingCount(MAX_ATTEMPTS))

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Прогресс скачивания треков для прослушивания без сети"
                    setShowBadge(false)
                },
            )
        }
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Скачивание треков")
            .setContentText(if (total > 0) "$done из $total" else "Подготовка…")
            .setProgress(total.coerceAtLeast(1), done.coerceAtMost(total), total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "Пауза", cancel)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d("SukifyOffline", message)
    }

    companion object {
        const val NAME = "downloads"
        const val TAG = "sukify-downloads"
        /** После стольких неудач подряд трек ждёт ручного «Повторить» или новой ревизии. */
        const val MAX_ATTEMPTS = 3
        private const val PARALLEL = 3
        private const val BUFFER = 64 * 1024
        private const val PROGRESS_MS = 500L
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 2001
        private val COVER_SIZES = listOf(160, 640)
    }
}

/** Сверка загрузок раз в 12 часов — даже если приложение не открывали. */
@HiltWorker
class OfflineSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: OfflineSync,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (sync.sync()) Result.success() else Result.retry()

    companion object {
        const val NAME = "offline-sync"
    }
}
