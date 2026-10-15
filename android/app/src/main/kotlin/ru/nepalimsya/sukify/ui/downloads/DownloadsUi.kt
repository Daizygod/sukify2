package ru.nepalimsya.sukify.ui.downloads

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import ru.nepalimsya.sukify.data.offline.CollectionDownload
import ru.nepalimsya.sukify.data.offline.DownloadSettings
import ru.nepalimsya.sukify.data.offline.Downloads
import ru.nepalimsya.sukify.data.offline.OfflineState
import ru.nepalimsya.sukify.domain.Track

/** Загрузки для экранов: что скачано, можно ли играть без сети, кнопки ⬇. */
@Stable
class DownloadsUi(
    private val collections: State<Map<String, CollectionDownload>>,
    private val downloaded: State<Set<Long>>,
    private val onlyDownloaded: State<Boolean>,
    private val settings: State<DownloadSettings>,
    val actions: Downloads?,
) {
    fun collection(key: String): CollectionDownload = collections.value[key] ?: CollectionDownload.Off

    fun isDownloaded(trackId: Long): Boolean = trackId in downloaded.value

    /** Нет сети или включён офлайн-режим — играет только скачанное. */
    val offline: Boolean get() = onlyDownloaded.value

    val offlineMode: Boolean get() = settings.value.offlineMode

    fun isAvailable(track: Track): Boolean = track.playable && (!offline || track.id in downloaded.value)

    /** Трек скачан отдельно (track:ID), а не в составе подборки. */
    fun isTrackIncluded(trackId: Long): Boolean = "track:$trackId" in settings.value.includes

    companion object {
        /** Для превью и скриншот-тестов: ничего не скачано, сеть есть. */
        val Empty = DownloadsUi(
            mutableStateOf(emptyMap()),
            mutableStateOf(emptySet()),
            mutableStateOf(false),
            mutableStateOf(DownloadSettings()),
            actions = null,
        )
    }
}

val LocalDownloads = staticCompositionLocalOf { DownloadsUi.Empty }

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    state: OfflineState,
    val actions: Downloads,
) : ViewModel() {
    val collections: StateFlow<Map<String, CollectionDownload>> = state.collections
    val downloaded: StateFlow<Set<Long>> = state.downloaded
    val onlyDownloaded: StateFlow<Boolean> = state.onlyDownloaded
    val settings: StateFlow<DownloadSettings> = state.settings
}
