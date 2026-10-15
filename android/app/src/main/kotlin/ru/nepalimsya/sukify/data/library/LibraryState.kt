package ru.nepalimsya.sukify.data.library

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.core.network.apiError
import ru.nepalimsya.sukify.data.offline.OutboxDao
import ru.nepalimsya.sukify.data.offline.OutboxEntity
import ru.nepalimsya.sukify.data.cache.ApiCache
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.data.model.PinDto
import ru.nepalimsya.sukify.data.model.PlaylistDto
import ru.nepalimsya.sukify.data.model.PlaylistRequest
import ru.nepalimsya.sukify.data.model.ReleaseDto
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.ui.components.Toasts

/** Что закрепляется в медиатеке (library_pins.item_type). */
enum class PinType(val api: String) { Playlist("playlist"), Album("album"), Artist("artist") }

/**
 * Медиатека пользователя — порт stores/library.js: лайки треков, плейлисты,
 * альбомы, подписки и закрепления. По лайкам горят «✓» во всех списках, в
 * плеере и в уведомлении. Любое переключение — сразу на экране, запрос —
 * следом; не вышло — откатываем и говорим об этом.
 */
@Singleton
class LibraryState @Inject constructor(
    private val api: SukifyApi,
    private val cache: ApiCache,
    private val outbox: OutboxDao,
) {
    private val _likedIds = MutableStateFlow<Set<Long>>(emptySet())
    val likedIds: StateFlow<Set<Long>> = _likedIds.asStateFlow()

    private val _playlists = MutableStateFlow<List<PlaylistDto>>(emptyList())
    val playlists: StateFlow<List<PlaylistDto>> = _playlists.asStateFlow()

    private val _albums = MutableStateFlow<List<ReleaseDto>>(emptyList())
    val albums: StateFlow<List<ReleaseDto>> = _albums.asStateFlow()

    private val _artists = MutableStateFlow<List<ArtistDto>>(emptyList())
    val artists: StateFlow<List<ArtistDto>> = _artists.asStateFlow()

    private val _pins = MutableStateFlow<Set<String>>(emptySet())
    val pins: StateFlow<Set<String>> = _pins.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * Прошлый снимок медиатеки уже поднят с диска (или пришёл свежий). До этого
     * главная не рисует шорткаты — иначе плитки «прыгают», когда плейлисты
     * появляются на долю секунды позже релизов.
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** Плейлист изменился (добавили трек, переименовали) — открытые экраны перечитывают. */
    private val _playlistChanged = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val playlistChanged: SharedFlow<Long> = _playlistChanged.asSharedFlow()

    /**
     * Медиатеку поменяли с этого телефона (лайк, альбом, подписка, плейлист):
     * загрузки сверяются, не дожидаясь события с сервера.
     */
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    /** Сообщить подписчикам (уведомление плеера), что лайк поменялся. */
    var onLikesChanged: () -> Unit = {}

    fun isLiked(trackId: Long): Boolean = trackId in _likedIds.value

    fun isAlbumLiked(releaseId: Long): Boolean = _albums.value.any { it.id == releaseId }

    fun isFollowing(artistId: Long): Boolean = _artists.value.any { it.id == artistId }

    fun isPinned(type: PinType, id: Long): Boolean = pinKey(type, id) in _pins.value

    /** Холодный старт: сначала прошлый снимок медиатеки, затем свежий с сервера. */
    suspend fun restoreCached() {
        cache.read(KEY_LIKED_IDS, ListSerializer(Long.serializer()))?.let {
            if (_likedIds.value.isEmpty()) _likedIds.value = it.toSet()
            onLikesChanged()
        }
        cache.read(KEY_PLAYLISTS, ListSerializer(PlaylistDto.serializer()))?.let { _playlists.value = it }
        cache.read(KEY_ALBUMS, ListSerializer(ReleaseDto.serializer()))?.let { _albums.value = it }
        cache.read(KEY_ARTISTS, ListSerializer(ArtistDto.serializer()))?.let { _artists.value = it }
        _ready.value = true
    }

    suspend fun refresh() = coroutineScope {
        // Сначала отправить сделанное без сети — иначе свежий ответ сервера затрёт эти правки.
        flushOutbox()
        val likes = async { refreshLikes() }
        val playlists = async { refreshPlaylists() }
        val albums = async { refreshAlbums() }
        val artists = async { refreshArtists() }
        val pins = async { apiCall { api.pins().data }.onSuccess { list -> _pins.value = list.map { pinKey(it.itemType, it.itemId) }.toSet() } }
        likes.await(); playlists.await(); albums.await(); artists.await(); pins.await()
        _loaded.value = true
        _ready.value = true
    }

    suspend fun refreshLikes() {
        apiCall { api.likedTrackIds() }.onSuccess { ids ->
            _likedIds.value = ids.toSet()
            onLikesChanged()
            cache.write(KEY_LIKED_IDS, ListSerializer(Long.serializer()), ids)
        }
    }

    suspend fun refreshPlaylists() {
        apiCall { api.libraryPlaylists().data }.onSuccess {
            _playlists.value = it
            cache.write(KEY_PLAYLISTS, ListSerializer(PlaylistDto.serializer()), it)
        }
    }

    suspend fun refreshAlbums() {
        apiCall { api.likedAlbums().data }.onSuccess {
            _albums.value = it
            cache.write(KEY_ALBUMS, ListSerializer(ReleaseDto.serializer()), it)
        }
    }

    suspend fun refreshArtists() {
        apiCall { api.followedArtists().data }.onSuccess {
            _artists.value = it
            cache.write(KEY_ARTISTS, ListSerializer(ArtistDto.serializer()), it)
        }
    }

    // --- Лайки треков -------------------------------------------------------

    suspend fun toggleLike(track: Track): Boolean {
        val liked = !isLiked(track.id)
        _likedIds.update { if (liked) it + track.id else it - track.id }
        onLikesChanged()
        val result = apiCall { if (liked) api.likeTrack(track.id) else api.unlikeTrack(track.id) }
        if (result.isFailure) {
            if (result.exceptionOrNull()?.apiError?.isNetwork == true) {
                // Без сети: лайк остаётся, на сервер уйдёт, когда появится связь.
                queue(if (liked) "like_track" else "unlike_track", track.id.toString())
            } else {
                _likedIds.update { if (liked) it - track.id else it + track.id }
                onLikesChanged()
                Toasts.show(NO_CONNECTION)
                return !liked
            }
        }
        cache.write(KEY_LIKED_IDS, ListSerializer(Long.serializer()), _likedIds.value.toList())
        Toasts.show(if (liked) "Добавлено в «Любимые треки»" else "Удалено из «Любимых треков»")
        _changes.tryEmit("liked")
        return liked
    }

    // --- Альбомы и подписки ---------------------------------------------------

    suspend fun toggleAlbumLike(release: ReleaseDto): Boolean {
        // Пока медиатека не загрузилась, верим флагу из ответа сервера.
        val like = if (_loaded.value) !isAlbumLiked(release.id) else release.isLiked != true
        val before = _albums.value
        _albums.value = if (like) listOf(release.copy(isLiked = true)) + before else before.filterNot { it.id == release.id }
        val result = apiCall { if (like) api.likeRelease(release.id) else api.unlikeRelease(release.id) }
        if (result.isFailure) {
            if (result.exceptionOrNull()?.apiError?.isNetwork == true) {
                queue(if (like) "like_album" else "unlike_album", release.id.toString())
            } else {
                _albums.value = before
                Toasts.show(NO_CONNECTION)
                return !like
            }
        }
        Toasts.show(if (like) "Добавлено в медиатеку" else "Удалено из медиатеки")
        cache.write(KEY_ALBUMS, ListSerializer(ReleaseDto.serializer()), _albums.value)
        _changes.tryEmit("albums")
        return like
    }

    suspend fun toggleFollow(artist: ArtistDto): Boolean {
        val follow = if (_loaded.value) !isFollowing(artist.id) else artist.isFollowed != true
        val before = _artists.value
        _artists.value = if (follow) listOf(artist.copy(isFollowed = true)) + before else before.filterNot { it.id == artist.id }
        val result = apiCall { if (follow) api.followArtist(artist.slug) else api.unfollowArtist(artist.slug) }
        if (result.isFailure) {
            if (result.exceptionOrNull()?.apiError?.isNetwork == true) {
                queue(if (follow) "follow" else "unfollow", artist.slug)
            } else {
                _artists.value = before
                Toasts.show(NO_CONNECTION)
                return !follow
            }
        }
        cache.write(KEY_ARTISTS, ListSerializer(ArtistDto.serializer()), _artists.value)
        _changes.tryEmit("artists")
        return follow
    }

    // --- Без сети ----------------------------------------------------------

    /** Отложить действие до появления сети; противоположные друг друга гасят. */
    private suspend fun queue(action: String, ref: String) {
        val opposite = OPPOSITE[action] ?: return
        if (outbox.deleteFor(ref, listOf(opposite)) == 0) outbox.insert(OutboxEntity(action = action, ref = ref))
    }

    /** Отправить накопленное без сети — по порядку; сеть снова пропала — остальное потом. */
    suspend fun flushOutbox() {
        for (row in outbox.all()) {
            val result = apiCall {
                when (row.action) {
                    "like_track" -> api.likeTrack(row.ref.toLong())
                    "unlike_track" -> api.unlikeTrack(row.ref.toLong())
                    "like_album" -> api.likeRelease(row.ref.toLong())
                    "unlike_album" -> api.unlikeRelease(row.ref.toLong())
                    "follow" -> api.followArtist(row.ref)
                    "unfollow" -> api.unfollowArtist(row.ref)
                    else -> Unit
                }
            }
            if (result.exceptionOrNull()?.apiError?.isNetwork == true) return
            // Успех или отказ сервера (трек удалили) — в любом случае больше не повторяем.
            outbox.delete(row.id)
        }
    }

    // --- Закрепления --------------------------------------------------------

    suspend fun togglePin(type: PinType, id: Long): Boolean {
        val key = pinKey(type, id)
        val pin = key !in _pins.value
        _pins.update { if (pin) it + key else it - key }
        val body = PinDto(type.api, id)
        val result = apiCall { if (pin) api.pin(body) else api.unpin(body) }
        if (result.isFailure) {
            _pins.update { if (pin) it - key else it + key }
            Toasts.show(NO_CONNECTION)
            return !pin
        }
        Toasts.show(if (pin) "Закреплено в медиатеке" else "Откреплено")
        return pin
    }

    // --- Плейлисты ----------------------------------------------------------

    suspend fun createPlaylist(title: String, description: String? = null): PlaylistDto? {
        val created = apiCall { api.createPlaylist(PlaylistRequest(title = title, description = description)).data }
            .onFailure { Toasts.show("Не получилось создать плейлист") }
            .getOrNull() ?: return null
        _playlists.update { listOf(created) + it.filterNot { p -> p.id == created.id } }
        _changes.tryEmit("playlists")
        refreshPlaylists()
        return created
    }

    suspend fun addToPlaylist(playlist: PlaylistDto, track: Track): Boolean {
        val result = apiCall { api.addToPlaylist(playlist.id, buildJsonObject { put("track_id", track.id) }) }
        if (result.isFailure) {
            Toasts.show("Не получилось добавить — попробуй ещё раз")
            return false
        }
        Toasts.show("Добавлено в плейлист «${playlist.title}»")
        _playlistChanged.tryEmit(playlist.id)
        _changes.tryEmit("playlists")
        refreshPlaylists()
        return true
    }

    suspend fun removeFromPlaylist(playlistId: Long, itemId: Long): Boolean {
        val ok = apiCall { api.removeFromPlaylist(playlistId, itemId) }.isSuccess
        if (!ok) Toasts.show(NO_CONNECTION) else {
            _changes.tryEmit("playlists")
            refreshPlaylists()
        }
        return ok
    }

    suspend fun deletePlaylist(playlist: PlaylistDto): Boolean {
        val before = _playlists.value
        _playlists.value = before.filterNot { it.id == playlist.id }
        val ok = apiCall { api.deletePlaylist(playlist.id) }.isSuccess
        if (!ok) {
            _playlists.value = before
            Toasts.show("Не получилось удалить плейлист")
        } else {
            Toasts.show("Плейлист удалён")
            _changes.tryEmit("playlists")
        }
        return ok
    }

    /** Плейлист поменяли на его экране — список в медиатеке подтягиваем следом. */
    fun updatePlaylist(playlist: PlaylistDto) {
        _playlists.update { list -> list.map { if (it.id == playlist.id) playlist.copy(tracks = null) else it } }
        _playlistChanged.tryEmit(playlist.id)
    }

    fun notifyPlaylistChanged(id: Long) {
        _playlistChanged.tryEmit(id)
        _changes.tryEmit("playlists")
    }

    /** Выход из аккаунта: неотправленные действия чужого аккаунта не нужны. */
    suspend fun clearOutbox() = outbox.clear()

    fun clear() {
        _likedIds.value = emptySet()
        _playlists.value = emptyList()
        _albums.value = emptyList()
        _artists.value = emptyList()
        _pins.value = emptySet()
        _loaded.value = false
        _ready.value = false
    }

    private fun pinKey(type: PinType, id: Long) = "${type.api}:$id"

    private fun pinKey(type: String, id: Long) = "$type:$id"

    private companion object {
        const val KEY_LIKED_IDS = "library:liked-ids"
        const val KEY_PLAYLISTS = "library:playlists"
        val OPPOSITE = mapOf(
            "like_track" to "unlike_track",
            "unlike_track" to "like_track",
            "like_album" to "unlike_album",
            "unlike_album" to "like_album",
            "follow" to "unfollow",
            "unfollow" to "follow",
        )
        const val KEY_ALBUMS = "library:albums"
        const val KEY_ARTISTS = "library:artists"
        const val NO_CONNECTION = "Не получилось — нет связи с сервером"
    }
}
