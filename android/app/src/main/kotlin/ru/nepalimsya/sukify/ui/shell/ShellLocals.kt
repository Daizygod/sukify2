package ru.nepalimsya.sukify.ui.shell

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import ru.nepalimsya.sukify.data.model.PlaylistDto
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.ContextPlayer
import ru.nepalimsya.sukify.playback.PlayerState

/**
 * Плеер для любых экранов: что играет и кнопки «играть подборку». Чтение
 * [current] подписывает композицию на смену трека/паузы (не на позицию).
 */
@Stable
class PlaybackUi(private val state: State<PlayerState>, val contexts: ContextPlayer) {
    val current: PlayerState get() = state.value

    val playingTrackId: Long? get() = current.track?.id

    fun isContext(key: String?): Boolean = !key.isNullOrEmpty() && current.queue.contextKey == key

    fun isPlaying(key: String?): Boolean = isContext(key) && current.playing
}

val LocalPlayback = staticCompositionLocalOf<PlaybackUi> { error("PlaybackUi не предоставлен") }

/**
 * Звук идёт (не пауза) — эквалайзер у играющей строки пляшет только тогда.
 * State, а не Boolean: читает его строка, а не вся оболочка.
 */
val LocalAudible = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/**
 * Полноэкранные инструменты (редактор перехода, правка плейлиста) прячут
 * нижнюю панель и мини-плеер — им нужен весь экран.
 */
@Stable
class ChromeController {
    private var requests by mutableStateOf(0)

    val hidden: Boolean get() = requests > 0

    fun hide() {
        requests++
    }

    fun show() {
        requests = (requests - 1).coerceAtLeast(0)
    }
}

val LocalChrome = staticCompositionLocalOf { ChromeController() }

/** Экран на весь экран: пока он виден, нижняя панель и мини-плеер скрыты. */
@androidx.compose.runtime.Composable
fun HideChrome() {
    val chrome = LocalChrome.current
    androidx.compose.runtime.DisposableEffect(chrome) {
        chrome.hide()
        onDispose { chrome.show() }
    }
}

/** Откуда открыли меню трека — от этого зависят пункты «Удалить из …». */
@Immutable
data class TrackMenuOptions(
    /** Трек в «Далее в очереди» (qid) или в продолжении контекста (индекс). */
    val queueQid: Long? = null,
    val upcomingIndex: Int? = null,
    /** Плейлист, где трек можно удалить (свой или совместный). */
    val playlist: PlaylistDto? = null,
    val playlistItemId: Long? = null,
    /** Открыто с экрана «Сейчас играет» — там есть «Таймер сна». */
    val nowPlaying: Boolean = false,
)

sealed interface MenuRequest {
    data class TrackMenu(val track: Track, val options: TrackMenuOptions) : MenuRequest

    data class Entity(val item: MediaItem, val tracks: List<Track>? = null, val playlist: PlaylistDto? = null) : MenuRequest

    data class AddToPlaylist(val track: Track) : MenuRequest

    data object SleepTimer : MenuRequest
}

/** Меню-шиты поверх всего: открываются с любого экрана, живут в оболочке. */
@Stable
class MenuController {
    var request by mutableStateOf<MenuRequest?>(null)
        private set

    fun track(track: Track, options: TrackMenuOptions = TrackMenuOptions()) {
        request = MenuRequest.TrackMenu(track, options)
    }

    fun entity(item: MediaItem, tracks: List<Track>? = null, playlist: PlaylistDto? = null) {
        request = MenuRequest.Entity(item, tracks, playlist)
    }

    fun sleepTimer() {
        request = MenuRequest.SleepTimer
    }

    fun addToPlaylist(track: Track) {
        request = MenuRequest.AddToPlaylist(track)
    }

    fun close() {
        request = null
    }
}

val LocalMenus = staticCompositionLocalOf<MenuController> { error("MenuController не предоставлен") }
