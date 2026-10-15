package ru.nepalimsya.sukify.data.offline

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.di.ApplicationScope
import ru.nepalimsya.sukify.di.IoDispatcher
import ru.nepalimsya.sukify.ui.components.Toasts

/** Как прошла последняя сверка с сервером. */
data class SyncStatus(
    val running: Boolean = false,
    /** Когда последний раз успешно сверились, unix-мс (0 — ещё не было). */
    val lastSyncAt: Long = 0,
    val error: String? = null,
)

/**
 * Сверка загрузок с сервером (OfflineSyncWorker и события вызывают её же):
 *
 *  1. манифест с If-None-Match — 304, если в медиатеке ничего не поменялось;
 *  2. копия медиатеки в Room (экраны без сети и «Любимые» на тысячи строк);
 *  3. что должно лежать на телефоне = выбранные подборки по правилам;
 *  4. нет на телефоне — в очередь; сменилась ревизия — перекачать (старый
 *     файл играет, пока не готов новый); больше не нужно — удалить.
 *     Трек удалили в Sukify, убрали из плейлиста, сняли лайк — он исчезает
 *     и из манифеста, и с телефона.
 */
@Singleton
class OfflineSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SukifyApi,
    private val json: Json,
    private val dao: OfflineCatalogDao,
    private val downloads: DownloadDao,
    private val lyrics: OfflineLyricsDao,
    private val transitions: OfflineTransitionDao,
    private val rules: DownloadRules,
    private val storage: OfflineStorage,
    private val catalog: OfflineCatalog,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private var pending: Job? = null

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Какие подборки сейчас выбраны для загрузки — для кнопок ⬇ на экранах. */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /** Подборки, которые качаются по автокатегориям (их «удалить» = исключить). */
    private val _auto = MutableStateFlow<Set<String>>(emptySet())
    val auto: StateFlow<Set<String>> = _auto.asStateFlow()

    /** Состав подборок (ключ → id треков) — чтобы кнопка ⬇ знала свой прогресс. */
    private val _sources = MutableStateFlow<Map<String, List<Long>>>(emptyMap())
    val sources: StateFlow<Map<String, List<Long>>> = _sources.asStateFlow()

    private val workManager get() = WorkManager.getInstance(context)

    /** Поднять прошлое состояние с диска: выбранные подборки, обложки, прерванные загрузки. */
    fun start() {
        scope.launch(io) {
            downloads.resetInterrupted()
            storage.refreshCoverIndex()
            mutex.withLock { plan(fetch = false) }
            schedulePeriodic()
        }
    }

    /**
     * Сверить «скоро»: события сыплются пачками (лайки подряд, импорт), а
     * сверка одна. Повторный вызов в окне ожидания сдвигает его.
     */
    fun requestSync(reason: String, delayMs: Long = 3_000) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            debug("sync: $reason")
            sync()
        }
    }

    /** Сверить сейчас; [fetch] = false — только пересчитать по уже известной медиатеке. */
    suspend fun sync(fetch: Boolean = true): Boolean = withContext(io) {
        mutex.withLock {
            _status.update { it.copy(running = true, error = null) }
            val ok = runCatching { plan(fetch) }
                .onFailure { Log.w(TAG, "sync failed", it) }
                .getOrDefault(false)
            _status.update { it.copy(running = false, lastSyncAt = if (ok) System.currentTimeMillis() else it.lastSyncAt) }
            ok
        }
    }

    /** Правила поменялись (⬇, галочки) — сразу пересчитать и сходить за манифестом. */
    fun rulesChanged() {
        scope.launch(io) {
            mutex.withLock { plan(fetch = false) }
            requestSync("rules", delayMs = 300)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun plan(fetch: Boolean): Boolean {
        val settings = rules.current()
        var fetched = true
        if (fetch) {
            val query = settings.includes.sorted().joinToString(",")
            val artists = if (settings.autoArtists) 1 else null
            val etag = dao.meta(META_ETAG)?.value?.takeIf { dao.meta(META_QUERY)?.value == "$query|$artists" }
            val response = apiCall { api.offlineManifest(query.ifEmpty { null }, artists, etag) }.getOrNull()
            when {
                response == null -> {
                    fetched = false
                    _status.update { it.copy(error = "Нет связи с сервером") }
                }
                response.code() == 304 -> debug("manifest: 304")
                response.isSuccessful -> {
                    val manifest = response.body()!!.byteStream().use { json.decodeFromStream(ManifestDto.serializer(), it) }
                    store(manifest, response.headers()["ETag"], "$query|$artists")
                    forgetMissing(manifest.missing, settings)
                    debug("manifest: ${manifest.tracks.size} tracks, ${manifest.sources.size} sources")
                }
                else -> {
                    fetched = false
                    _status.update { it.copy(error = "Сервер ответил ${response.code()}") }
                }
            }
        }
        apply(rules.current())
        return fetched
    }

    private suspend fun store(manifest: ManifestDto, etag: String?, query: String) {
        val tracks = manifest.tracks.map { t ->
            OfflineTrackEntity(
                id = t.id,
                title = t.title,
                releaseId = t.releaseId,
                trackNumber = t.trackNumber,
                durationMs = t.durationMs ?: 0,
                artistIds = t.artistIds.joinToString(","),
                lufs = t.lufs,
                bpm = t.bpm?.toInt(),
                camelot = t.camelot,
                preview = t.preview,
                unofficial = t.unofficial,
                ownCover = t.ownCover,
                audioRev = t.audio?.rev,
                audioSize = t.audio?.size,
            )
        }
        val releases = manifest.releases.map { (id, r) ->
            OfflineReleaseEntity(id, r.title, r.slug, r.type, r.year, r.artistId, r.coverV, r.bg)
        }
        val artists = manifest.artists.map { (id, a) ->
            OfflineArtistEntity(id, a.name, a.slug, a.avatarUrl, a.bannerUrl, a.bg)
        }
        val sources = manifest.sources.mapIndexed { i, s ->
            OfflineSourceEntity(
                key = s.key,
                type = s.type,
                refId = s.id,
                position = i,
                title = s.title,
                description = s.description,
                coverUrl = s.coverUrl,
                isPublic = s.isPublic ?: true,
                isOwner = s.isOwner ?: false,
                isCollaborative = s.isCollaborative ?: false,
                ownerName = s.owner?.name,
                ownerUsername = s.owner?.username,
                updatedAt = s.updatedAt,
                trackCount = s.trackIds.size,
            )
        }
        val sourceTracks = manifest.sources.flatMap { s ->
            s.trackIds.mapIndexed { i, id ->
                SourceTrackEntity(s.key, i, id, s.itemIds?.getOrNull(i), s.addedAt?.getOrNull(i))
            }
        }
        dao.replaceAll(tracks, releases, artists, sources, sourceTracks)
        catalog.saveUrls(manifest.urls)
        dao.putMeta(OfflineMetaEntity(META_LIBRARY, json.encodeToString(ManifestLibrary.serializer(), manifest.library)))
        if (etag != null) dao.putMeta(OfflineMetaEntity(META_ETAG, etag))
        dao.putMeta(OfflineMetaEntity(META_QUERY, query))
    }

    /** Подборка, скачанная вручную, пропала (удалили, закрыли) — забываем и говорим. */
    private suspend fun forgetMissing(missing: List<String>, settings: DownloadSettings) {
        val gone = missing.filter { it in settings.includes || it in settings.excludes }
        if (gone.isEmpty()) return
        rules.forget(gone)
        val playlists = gone.count { it.startsWith("playlist:") }
        Toasts.show(
            when {
                playlists > 0 -> "Плейлист больше недоступен — убран из загрузок"
                else -> "Часть загрузок больше недоступна в Sukify — убрана с телефона"
            },
        )
    }

    /** Сравнить желаемое с тем, что лежит на телефоне, и привести одно к другому. */
    private suspend fun apply(settings: DownloadSettings) {
        val library = dao.meta(META_LIBRARY)?.value
            ?.let { runCatching { json.decodeFromString(ManifestLibrary.serializer(), it) }.getOrNull() }
            ?: ManifestLibrary()
        val sourceRows = dao.allSourceTracks()
        val sourceTracks = sourceRows.groupBy({ it.sourceKey }, { it.trackId })
        val available = dao.sources().mapTo(HashSet()) { it.key }
        val selected = DownloadPlanner.selectedKeys(settings, library, available)
        val audio = dao.tracks().mapNotNull { t ->
            val rev = t.audioRev ?: return@mapNotNull null
            t.id to DownloadPlanner.Audio(rev, t.audioSize ?: 0, t.preview)
        }.toMap()
        val desired = DownloadPlanner.desired(selected, sourceTracks, audio, settings.previews)
        val diff = DownloadPlanner.diff(desired, downloads.all())

        _selected.value = selected
        _auto.value = DownloadPlanner.autoKeys(settings, library)
        _sources.value = sourceTracks

        if (diff.remove.isNotEmpty()) {
            for (row in diff.remove) deleteFiles(row)
            downloads.delete(diff.remove.map { it.trackId })
            lyrics.pruneOrphans()
            debug("removed ${diff.remove.size} downloads")
        }
        if (diff.enqueue.isNotEmpty()) {
            downloads.upsert(diff.enqueue)
            debug("queued ${diff.enqueue.size} downloads")
        }
        collectGarbage()
        if (downloads.pendingCount(DownloadWorker.MAX_ATTEMPTS) > 0 || diff.remove.isNotEmpty() || diff.enqueue.isNotEmpty()) {
            scheduleDownloads(settings)
        }
    }

    private fun deleteFiles(row: DownloadEntity) {
        row.path?.let { File(it).delete() }
        for (volume in StorageVolume.entries) {
            val dir = storage.root(volume)?.let { File(it, "audio") } ?: continue
            dir.listFiles { f -> f.name.startsWith("${row.trackId}.") }?.forEach { it.delete() }
        }
    }

    /** Файлы без записи (прерванная загрузка, старая ревизия) — удалить. */
    private suspend fun collectGarbage() {
        val rows = downloads.all()
        val keep = HashSet<String>()
        for (row in rows) {
            row.path?.let { keep += File(it).name }
            keep += "${row.trackId}.${row.rev}.mp3"
            keep += "${row.trackId}.${row.rev}.mp3.part"
        }
        for (volume in StorageVolume.entries) {
            val dir = storage.root(volume)?.let { File(it, "audio") } ?: continue
            dir.listFiles()?.forEach { f -> if (f.name !in keep) f.delete() }
        }
    }

    /**
     * Сколько займёт каждая автокатегория — показываем до включения галочки,
     * чтобы «Вся медиатека» не стала сюрпризом на 20 ГБ.
     */
    suspend fun estimates(): Map<String, Long> = withContext(io) {
        val library = dao.meta(META_LIBRARY)?.value
            ?.let { runCatching { json.decodeFromString(ManifestLibrary.serializer(), it) }.getOrNull() }
            ?: return@withContext emptyMap()
        val sizes = dao.tracks().associate { it.id to (it.audioSize ?: 0L) }
        val byKey = dao.allSourceTracks().groupBy({ it.sourceKey }, { it.trackId })
        fun total(keys: List<String>) = keys.flatMap { byKey[it].orEmpty() }.distinct().sumOf { sizes[it] ?: 0L }
        buildMap {
            put("liked", total(listOf(DownloadPlanner.LIKED)))
            put("playlists", total(library.playlists.map { "playlist:$it" }))
            put("albums", total(library.albums.map { "release:$it" }))
            val artistKeys = library.artists.map { "artist:$it" }.filter { it in byKey }
            if (artistKeys.isNotEmpty()) put("artists", total(artistKeys))
        }
    }

    /** Загрузчик: по Wi-Fi или по любой сети — как разрешено в настройках. */
    fun scheduleDownloads(settings: DownloadSettings, replace: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.cellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .addTag(DownloadWorker.TAG)
            .build()
        workManager.enqueueUniqueWork(
            DownloadWorker.NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    /** Раз в 12 часов — сверка в фоне, даже если приложение не открывали. */
    private fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(OfflineSyncWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Выход из аккаунта: загрузки, копия медиатеки и правила — стереть. */
    suspend fun wipe() = withContext(io) {
        mutex.withLock {
            workManager.cancelUniqueWork(DownloadWorker.NAME)
            workManager.cancelUniqueWork(OfflineSyncWorker.NAME)
            downloads.clear()
            lyrics.clear()
            transitions.clear()
            dao.replaceAll(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
            dao.clearMeta()
            catalog.forgetUrls()
            rules.clear()
            storage.wipe()
            _selected.value = emptySet()
            _auto.value = emptySet()
            _sources.value = emptyMap()
            _status.value = SyncStatus()
        }
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "SukifyOffline"
        const val META_ETAG = "etag"
        const val META_QUERY = "query"
        const val META_LIBRARY = "library"
    }
}

