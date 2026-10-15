package ru.nepalimsya.sukify.data.offline

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.network.NetworkMonitor
import ru.nepalimsya.sukify.di.ApplicationScope
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.OfflineFiles

/** Что показывает кнопка ⬇ подборки. */
data class CollectionDownload(
    val state: State,
    val done: Int = 0,
    val total: Int = 0,
) {
    enum class State {
        /** Не качается. */
        Off,

        /** Выбрана, но ещё не сверились с сервером (или ждём сеть/Wi-Fi). */
        Waiting,

        /** Качается: кольцо прогресса. */
        Downloading,

        /** Всё скачано: зелёная стрелка. */
        Done,
    }

    val active: Boolean get() = state != State.Off

    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total

    companion object {
        val Off = CollectionDownload(State.Off)
    }
}

/** Общая сводка загрузок — для экрана «Загрузки» и уведомления. */
data class DownloadsSummary(
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
) {
    val queued: Int get() = total - done - failed
}

/**
 * Где лежат скачанные файлы (для плеера) и что скачано (для интерфейса):
 * зелёные стрелки у треков, ⬇ у подборок, приглушённые строки без сети.
 */
@Singleton
class OfflineState @Inject constructor(
    downloads: DownloadDao,
    rules: DownloadRules,
    sync: OfflineSync,
    network: NetworkMonitor,
    @ApplicationScope scope: CoroutineScope,
) : OfflineFiles {

    @Volatile
    private var files: Map<Long, File> = emptyMap()

    private val rows = MutableStateFlow<List<DownloadEntity>>(emptyList())

    private val _downloaded = MutableStateFlow<Set<Long>>(emptySet())

    /** id треков, у которых на диске лежит файл (любой ревизии). */
    val downloaded: StateFlow<Set<Long>> = _downloaded.asStateFlow()

    val settings: StateFlow<DownloadSettings> = rules.settings

    /** Играть можно только скачанное: офлайн-режим включён или сети нет. */
    val onlyDownloaded: StateFlow<Boolean> = combine(network.state, rules.settings) { net, s -> s.offlineMode || !net.online }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Ключ подборки → состояние кнопки ⬇. */
    val collections: StateFlow<Map<String, CollectionDownload>> =
        combine(sync.selected, sync.sources, rows, rules.settings) { selected, sources, list, settings ->
            val byId = list.associateBy { it.trackId }
            buildMap {
                for (key in selected) {
                    val ids = sources[key].orEmpty()
                    val tracked = ids.mapNotNull(byId::get)
                    val done = tracked.count { it.state == DownloadState.DONE }
                    val state = when {
                        tracked.isEmpty() -> CollectionDownload.State.Waiting
                        done == tracked.size -> CollectionDownload.State.Done
                        else -> CollectionDownload.State.Downloading
                    }
                    put(key, CollectionDownload(state, done, tracked.size))
                }
                // Нажали ⬇, а подборки ещё нет в манифесте (чужой плейлист) — ждём сверки.
                for (key in settings.includes) {
                    if (key !in selected) put(key, CollectionDownload(CollectionDownload.State.Waiting))
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val summary: StateFlow<DownloadsSummary> = rows.combine(rules.settings) { list, _ ->
        DownloadsSummary(
            total = list.size,
            done = list.count { it.state == DownloadState.DONE },
            failed = list.count { it.state == DownloadState.FAILED },
            bytesDone = list.sumOf { if (it.state == DownloadState.DONE) it.size else it.bytes },
            bytesTotal = list.sumOf { it.size },
        )
    }.stateIn(scope, SharingStarted.Eagerly, DownloadsSummary())

    /** Строки загрузок как есть — для очереди на экране «Загрузки». */
    val queue: StateFlow<List<DownloadEntity>> = rows.asStateFlow()

    init {
        scope.launch {
            downloads.observeAll().collect { list ->
                rows.value = list
                val map = HashMap<Long, File>(list.size)
                for (row in list) {
                    val path = row.path ?: continue
                    if (row.fileRev != null) map[row.trackId] = File(path)
                }
                files = map
                _downloaded.value = map.keys
            }
        }
    }

    /** Файл трека на диске (любой ревизии: новая докачивается — играет старая). */
    override fun fileFor(track: Track): File? = files[track.id]?.takeIf { it.exists() }

    fun isDownloaded(trackId: Long): Boolean = trackId in files

    /** Можно ли включить трек прямо сейчас. */
    fun isAvailable(track: Track): Boolean = track.playable && (!onlyDownloaded.value || track.id in files)
}
