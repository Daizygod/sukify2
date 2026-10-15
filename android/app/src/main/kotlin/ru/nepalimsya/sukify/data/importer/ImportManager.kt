package ru.nepalimsya.sukify.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.core.network.apiError
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.model.ImportJobDto
import ru.nepalimsya.sukify.data.model.LikedImportDto
import ru.nepalimsya.sukify.di.ApplicationScope
import ru.nepalimsya.sukify.ui.components.Toasts

@Immutable
data class ImportUi(
    val job: ImportJobDto? = null,
    /** 0…1 — архив уходит на сервер; null — не грузим. */
    val upload: Float? = null,
    val csvBusy: Boolean = false,
    val csv: LikedImportDto? = null,
    val error: String? = null,
)

/**
 * Импорт из Spotify (ImportPage.vue): ZIP «Download your data» уходит на
 * сервер с прогрессом, дальше сервер разбирает его сам — следим за ним.
 * Живёт на уровне приложения: ушли с экрана — загрузка не обрывается.
 * CSV из Exportify разбираем на телефоне и лайкаем найденное.
 */
@Singleton
class ImportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SukifyApi,
    private val json: Json,
    private val library: LibraryState,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(ImportUi())
    val ui: StateFlow<ImportUi> = _ui.asStateFlow()

    private var pollJob: Job? = null
    private var uploadJob: Job? = null

    /** Экран открылся: последний импорт и, если он идёт, — следим. */
    fun refresh() {
        if (_ui.value.upload != null) return
        scope.launch {
            val element = apiCall { api.latestImport() }.getOrNull() ?: return@launch
            val job = (element as? JsonObject)?.let { runCatching { json.decodeFromJsonElement(ImportJobDto.serializer(), it) }.getOrNull() }
            _ui.update { it.copy(job = job) }
            if (job?.running == true) poll(job.id)
        }
    }

    fun pick(uri: Uri) {
        val name = displayName(uri) ?: "archive.zip"
        _ui.update { it.copy(error = null, csv = null) }
        if (name.lowercase().endsWith(".csv")) importCsv(uri) else upload(uri, name)
    }

    fun cancelEnrich() {
        val id = _ui.value.job?.id ?: return
        scope.launch {
            apiCall { api.cancelImport(id) }.onSuccess { job ->
                pollJob?.cancel()
                _ui.update { it.copy(job = job) }
            }
        }
    }

    private fun upload(uri: Uri, name: String) {
        if (uploadJob?.isActive == true) return
        uploadJob = scope.launch {
            _ui.update { it.copy(upload = 0f) }
            val size = sizeOf(uri)
            val body = UriBody(uri, size, mimeOf(name)) { sent ->
                if (size > 0) _ui.update { it.copy(upload = (sent.toFloat() / size).coerceIn(0f, 1f)) }
            }
            apiCall { api.uploadImport(MultipartBody.Part.createFormData("archive", name, body)) }
                .onSuccess { job ->
                    _ui.update { it.copy(job = job, upload = null) }
                    poll(job.id)
                }
                .onFailure { e ->
                    _ui.update {
                        it.copy(
                            upload = null,
                            error = e.apiError.message.takeIf { m -> m.isNotBlank() && e.apiError.code != null }
                                ?: "Не смог принять файл. Нужен ZIP из «Download your data» (или CSV из Exportify).",
                        )
                    }
                }
        }
    }

    private fun poll(id: Long) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(2_000)
                val job = apiCall { api.importJob(id) }.getOrNull() ?: continue
                val wasRunning = _ui.value.job?.running == true
                _ui.update { it.copy(job = job) }
                if (!job.running) {
                    if (wasRunning) {
                        library.refresh()
                        if (job.status == "done") Toasts.show("Импорт из Spotify готов")
                    }
                    break
                }
            }
        }
    }

    private fun importCsv(uri: Uri) {
        scope.launch {
            _ui.update { it.copy(csvBusy = true) }
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }
            val items = text?.let(Exportify::parse).orEmpty()
            if (items.isEmpty()) {
                _ui.update { it.copy(csvBusy = false, error = "Не нашёл треков в CSV. Это точно выгрузка Exportify?") }
                return@launch
            }
            val body = buildJsonObject {
                put(
                    "items",
                    buildJsonArray {
                        items.take(5000).forEach { item ->
                            add(
                                buildJsonObject {
                                    put("title", item.title)
                                    put("artists", buildJsonArray { item.artists.forEach { a -> add(JsonPrimitive(a)) } })
                                    item.durationMs?.let { put("duration_ms", it) }
                                },
                            )
                        }
                    },
                )
            }
            apiCall { api.importLiked(body) }
                .onSuccess { r ->
                    _ui.update { it.copy(csvBusy = false, csv = r) }
                    library.refresh()
                    Toasts.show("Импорт готов: добавлено ${r.added}")
                }
                .onFailure { _ui.update { it.copy(csvBusy = false, error = "Не смог разобрать CSV.") } }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L

    private fun mimeOf(name: String): MediaType = when {
        name.endsWith(".json", ignoreCase = true) -> "application/json"
        else -> "application/zip"
    }.toMediaType()

    /** Тело запроса прямо из выбранного файла — архив в сотни мегабайт в память не читаем. */
    private inner class UriBody(
        private val uri: Uri,
        private val size: Long,
        private val type: MediaType,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = type

        override fun contentLength(): Long = size

        override fun writeTo(sink: BufferedSink) {
            val input = context.contentResolver.openInputStream(uri) ?: error("файл недоступен")
            input.source().use { source ->
                var sent = 0L
                var reported = 0L
                while (true) {
                    val n = source.read(sink.buffer, 64 * 1024)
                    if (n < 0) break
                    sink.emitCompleteSegments()
                    sent += n
                    if (sent - reported > 256 * 1024) {
                        reported = sent
                        onProgress(sent)
                    }
                }
                onProgress(sent)
            }
        }
    }
}

/** Выгрузка «Liked Songs» из exportify.net: CSV с кавычками, порядок колонок разный. */
object Exportify {
    data class Item(val title: String, val artists: List<String>, val durationMs: Long?)

    fun parse(text: String): List<Item> {
        val rows = rows(text)
        val head = rows.firstOrNull()?.map { it.lowercase() } ?: return emptyList()
        fun col(vararg names: String) = head.indexOfFirst { h -> names.any { h.contains(it) } }
        val iTitle = col("track name", "название трека")
        if (iTitle < 0) return emptyList()
        val iArtists = col("artist name", "исполнител")
        val iDur = col("duration")
        return rows.drop(1).mapNotNull { r ->
            val title = r.getOrNull(iTitle).orEmpty()
            if (title.isBlank()) return@mapNotNull null
            Item(
                title = title,
                artists = r.getOrNull(iArtists).orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() },
                durationMs = if (iDur >= 0) r.getOrNull(iDur)?.trim()?.toLongOrNull() else null,
            )
        }
    }

    /** CSV с кавычками и "" внутри них (parseCsv в вебе). */
    fun rows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endRow() {
            row.add(cell.toString())
            cell.clear()
            if (row.any { it.isNotEmpty() }) rows.add(row)
            row = mutableListOf()
        }
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                when {
                    ch == '"' && text.getOrNull(i + 1) == '"' -> {
                        cell.append('"')
                        i++
                    }
                    ch == '"' -> quoted = false
                    else -> cell.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> quoted = true
                    ',' -> {
                        row.add(cell.toString())
                        cell.clear()
                    }
                    '\n', '\r' -> {
                        if (ch == '\r' && text.getOrNull(i + 1) == '\n') i++
                        endRow()
                    }
                    else -> cell.append(ch)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
