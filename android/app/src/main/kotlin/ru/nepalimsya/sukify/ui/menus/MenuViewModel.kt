package ru.nepalimsya.sukify.ui.menus

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.network.ApiHost
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.library.PinType
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.data.model.PlaylistDto
import ru.nepalimsya.sukify.data.model.ReleaseDto
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.MediaKind
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.playback.PlayContexts
import ru.nepalimsya.sukify.playback.PlaybackEngine
import ru.nepalimsya.sukify.playback.SleepState
import ru.nepalimsya.sukify.playback.SleepTimer
import ru.nepalimsya.sukify.ui.components.Toasts
import javax.inject.Inject

/** Действия меню трека и подборки (ContextMenu.vue). */
@HiltViewModel
class MenuViewModel @Inject constructor(
    private val engine: PlaybackEngine,
    private val library: LibraryState,
    private val contexts: PlayContexts,
    private val api: SukifyApi,
    private val host: ApiHost,
    @ApplicationContext private val context: Context,
    private val sleepTimer: SleepTimer,
) : ViewModel() {

    val sleep: StateFlow<SleepState> = sleepTimer.state

    fun sleepFor(minutes: Int) = sleepTimer.start(minutes)

    fun sleepEndOfTrack() = sleepTimer.endOfTrack()

    fun sleepCancel() = sleepTimer.cancel()

    val likedIds: StateFlow<Set<Long>> = library.likedIds
    val playlists: StateFlow<List<PlaylistDto>> = library.playlists
    val pins: StateFlow<Set<String>> = library.pins
    val albums: StateFlow<List<ReleaseDto>> = library.albums
    val artists: StateFlow<List<ArtistDto>> = library.artists

    // --- Трек ----------------------------------------------------------------

    fun toggleLike(track: Track) {
        viewModelScope.launch { library.toggleLike(track) }
    }

    fun addToQueue(track: Track) {
        if (engine.addToQueue(track)) Toasts.show("Добавлено в очередь")
    }

    fun removeFromQueue(qid: Long?, upcomingIndex: Int?) {
        when {
            qid != null -> engine.removeFromManual(qid)
            upcomingIndex != null -> engine.removeUpcoming(upcomingIndex)
            else -> return
        }
        Toasts.show("Удалено из очереди")
    }

    fun addToPlaylist(playlist: PlaylistDto, track: Track) {
        viewModelScope.launch { library.addToPlaylist(playlist, track) }
    }

    /** «Создать плейлист» из меню: сразу с этим треком (createAndAdd в вебе). */
    fun createAndAdd(title: String, track: Track) {
        viewModelScope.launch {
            val created = library.createPlaylist(title) ?: return@launch
            if (library.addToPlaylist(created, track)) Toasts.show("Создан плейлист «${created.title}»")
        }
    }

    fun removeFromPlaylist(playlist: PlaylistDto, itemId: Long) {
        viewModelScope.launch {
            if (library.removeFromPlaylist(playlist.id, itemId)) {
                Toasts.show("Удалено из плейлиста «${playlist.title}»")
                library.notifyPlaylistChanged(playlist.id)
            }
        }
    }

    fun startRadio(track: Track) {
        viewModelScope.launch {
            apiCall { api.trackRadio(track.id).data }
                .onSuccess { list ->
                    engine.playContext(listOf(track) + list.map { it.toDomain() }, track, "Радио: ${track.title}", "radio:${track.id}")
                    Toasts.show("Включаю радио по треку")
                }
                .onFailure { Toasts.show("Радио сейчас недоступно") }
        }
    }

    fun shareTrack(track: Track) {
        val release = track.release
        share(if (release != null) "${host.base}/release/${release.slug}?t=${track.id}" else host.base, track.title)
    }

    // --- Подборка ------------------------------------------------------------

    fun play(item: MediaItem, tracks: List<Track>?) {
        val key = item.contextKey ?: return
        contexts.toggle(key, item.title, tracks)
    }

    fun enqueue(item: MediaItem, tracks: List<Track>?) {
        if (tracks != null) contexts.enqueueTracks(tracks) else item.contextKey?.let(contexts::enqueue)
    }

    fun pinType(item: MediaItem): PinType? = when (item.kind) {
        MediaKind.Playlist -> PinType.Playlist
        MediaKind.Release -> PinType.Album
        MediaKind.Artist -> PinType.Artist
        else -> null
    }

    fun togglePin(item: MediaItem) {
        val type = pinType(item) ?: return
        viewModelScope.launch { library.togglePin(type, item.id) }
    }

    fun toggleAlbum(item: MediaItem) {
        viewModelScope.launch {
            val release = library.albums.value.firstOrNull { it.id == item.id }
                ?: ReleaseDto(id = item.id, title = item.title, slug = item.ref)
            library.toggleAlbumLike(release)
        }
    }

    fun toggleFollow(item: MediaItem) {
        viewModelScope.launch {
            val artist = library.artists.value.firstOrNull { it.id == item.id }
                ?: ArtistDto(id = item.id, name = item.title, slug = item.ref)
            library.toggleFollow(artist)
        }
    }

    fun deletePlaylist(playlist: PlaylistDto, onDone: () -> Unit) {
        viewModelScope.launch { if (library.deletePlaylist(playlist)) onDone() }
    }

    /** Совместный плейлист: ссылка-приглашение уходит через системное «Поделиться». */
    fun invite(playlist: PlaylistDto) {
        viewModelScope.launch {
            apiCall { api.invite(playlist.id) }
                .onSuccess { share("${host.base}/playlist/${playlist.id}?join=${it.inviteToken}", "Присоединяйся к плейлисту «${playlist.title}»") }
                .onFailure { Toasts.show("Не получилось создать приглашение") }
        }
    }

    fun shareItem(item: MediaItem) {
        val path = when (item.kind) {
            MediaKind.Release -> "/release/${item.ref}"
            MediaKind.Playlist -> "/playlist/${item.ref}"
            MediaKind.Artist -> "/artist/${item.ref}"
            MediaKind.Mix -> "/mix/${item.ref}"
            MediaKind.User -> "/user/${item.ref}"
            MediaKind.Liked -> "/liked"
        }
        share(host.base + path, item.title)
    }

    private fun share(url: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_TITLE, title)
        }
        context.startActivity(Intent.createChooser(send, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
