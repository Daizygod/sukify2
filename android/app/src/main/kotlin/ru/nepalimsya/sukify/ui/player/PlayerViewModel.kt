package ru.nepalimsya.sukify.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.connect.ConnectDevice
import ru.nepalimsya.sukify.connect.ConnectManager
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.data.model.LyricsDto
import ru.nepalimsya.sukify.data.offline.OfflineLyricsDao
import ru.nepalimsya.sukify.domain.Lyrics
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.PlaybackEngine
import ru.nepalimsya.sukify.playback.PlayerState
import ru.nepalimsya.sukify.playback.SleepState
import ru.nepalimsya.sukify.playback.SleepTimer
import ru.nepalimsya.sukify.playback.queue.QueuedTrack
import ru.nepalimsya.sukify.ui.components.Toasts
import javax.inject.Inject

/** Плеер для экранов: состояние движка, текст песни, карточка исполнителя. */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val engine: PlaybackEngine,
    private val library: LibraryState,
    private val api: SukifyApi,
    private val offlineLyrics: OfflineLyricsDao,
    private val connect: ConnectManager,
    private val sleepTimer: SleepTimer,
) : ViewModel() {

    val sleep: StateFlow<SleepState> = sleepTimer.state

    fun sleepRemaining(): Long? = sleepTimer.remainingMs()

    val state: StateFlow<PlayerState> = engine.state
    val likedIds: StateFlow<Set<Long>> = library.likedIds

    /** Sukify Connect: устройства, активное и его громкость, пока играет не здесь. */
    val devices: StateFlow<List<ConnectDevice>> = connect.devices
    val activeDeviceId: StateFlow<String?> = connect.activeId
    val remoteDevice: StateFlow<String?> = connect.remote.map { it?.device?.name }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val remoteVolume: StateFlow<Float?> = connect.remote.map { it?.vol }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _lyrics = MutableStateFlow<Lyrics?>(null)

    /** Текст текущего трека; null — ещё грузится или его нет. */
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _artist = MutableStateFlow<ArtistDto?>(null)
    val aboutArtist: StateFlow<ArtistDto?> = _artist.asStateFlow()

    private val lyricsCache = HashMap<Long, Lyrics>()
    private val artistCache = HashMap<String, ArtistDto>()

    init {
        // Текст и «Об исполнителе» — для каждого нового трека; поздний ответ по
        // старому треку не затрёт новый (collectLatest отменяет прошлый запрос).
        viewModelScope.launch {
            state.map { it.track }.distinctUntilChanged { a, b -> a?.id == b?.id }.collectLatest { track ->
                _lyrics.value = null
                if (track == null) return@collectLatest
                // Без сети — текст, скачанный вместе с треком.
                val dto = apiCall { api.lyrics(track.id) }.getOrNull()
                    ?: offlineLyrics.get(track.id)?.let { LyricsDto(found = it.synced != null || it.plain != null, synced = it.synced, plain = it.plain) }
                _lyrics.value = lyricsCache[track.id] ?: dto?.let {
                    Lyrics.parse(it.synced, it.plain).also { parsed -> lyricsCache[track.id] = parsed }
                }
            }
        }
        viewModelScope.launch {
            state.map { it.track?.artists?.firstOrNull()?.slug }.distinctUntilChanged().collectLatest { slug ->
                _artist.value = null
                // У трека с другого устройства, пока не подтянули полный, исполнитель без ссылки.
                if (slug.isNullOrBlank()) return@collectLatest
                _artist.value = artistCache[slug] ?: apiCall { api.artist(slug).data }.getOrNull()?.also { artistCache[slug] = it }
            }
        }
    }

    fun position(): Long = engine.positionMs()

    fun togglePlay() = engine.togglePlay()

    fun next() = engine.next()

    fun previous(force: Boolean = false) = engine.previous(force)

    fun seek(ms: Long) = engine.seekTo(ms)

    fun toggleShuffle() = engine.setShuffle(!state.value.queue.shuffle)

    fun cycleRepeat() = engine.cycleRepeat()

    fun toggleLike(track: Track) {
        viewModelScope.launch { library.toggleLike(track) }
    }

    fun addToQueue(track: Track) {
        if (engine.addToQueue(track)) Toasts.show("Добавлено в очередь")
    }

    fun playManual(qid: Long) = engine.playManual(qid)

    fun playUpcoming(i: Int) = engine.playUpcoming(i)

    fun removeManual(qid: Long) = engine.removeFromManual(qid)

    fun removeUpcoming(i: Int) = engine.removeUpcoming(i)

    fun reorderManual(items: List<QueuedTrack>) = engine.setManual(items)

    fun reorderUpcoming(tracks: List<Track>) = engine.setUpcoming(tracks)

    fun clearManual() = engine.clearManual()

    fun transferTo(deviceId: String) = connect.transferTo(deviceId)

    fun setRemoteVolume(fraction: Float) = connect.setRemoteVolume(fraction)

    fun toggleFollow(artist: ArtistDto) {
        val follow = artist.isFollowed != true
        _artist.value = artist.copy(isFollowed = follow)
        viewModelScope.launch {
            val now = library.toggleFollow(artist)
            val updated = artist.copy(isFollowed = now)
            _artist.value = updated
            artistCache[artist.slug] = updated
        }
    }
}
