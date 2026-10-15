package ru.nepalimsya.sukify.playback.auto

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.core.datastore.SessionState
import ru.nepalimsya.sukify.core.datastore.SessionStore
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.offline.OfflineCatalog
import ru.nepalimsya.sukify.data.model.HomeDto
import ru.nepalimsya.sukify.data.model.ReleaseDto
import ru.nepalimsya.sukify.data.offline.OfflineState
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.domain.mainArtists
import ru.nepalimsya.sukify.domain.toDomain

/**
 * Дерево медиатеки для Android Auto и других браузеров MediaSession:
 * Главная (любимые, миксы дня), Медиатека (плейлисты, альбомы), Скачанное.
 * Играемый элемент несёт трек и его подборку: «t:{id}|{ключ подборки}» —
 * выбрал в машине трек плейлиста, дальше играет плейлист, как в приложении.
 */
@Singleton
class AutoLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val library: LibraryState,
    private val catalog: CatalogRepository,
    private val offline: OfflineState,
    private val offlineCatalog: OfflineCatalog,
    private val api: SukifyApi,
) {
    fun root(): MediaItem = browsable(ROOT, "Sukify", grid = false)

    /** Вошёл ли пользователь: без входа машине — подсказка, а не пустые вкладки. */
    suspend fun signedIn(): Boolean = sessions.state.first { it !is SessionState.Loading } is SessionState.Active

    suspend fun children(parentId: String): List<MediaItem> {
        // Процесс могла поднять машина — медиатека с диска, свежая подтянется следом.
        if (!library.ready.value) runCatching { library.restoreCached() }
        return childrenOf(parentId)
    }

    private suspend fun childrenOf(parentId: String): List<MediaItem> = when (parentId) {
        ROOT -> listOf(
            browsable(HOME, "Главная", grid = true),
            browsable(LIBRARY, "Медиатека", grid = true),
            browsable(DOWNLOADS, "Скачанное", grid = false),
        )
        HOME -> buildList {
            add(liked())
            val recent = recentTracks()
            if (recent.isNotEmpty()) add(browsable(RECENT, "Недавно слушали", subtitle = "Треки", artwork = artwork(recent.first().cover)))
            val mixes = runCatching { catalog.dailyMixes().first { it !is Loadable.Loading }.dataOrNull }.getOrNull().orEmpty()
            mixes.forEach { mix ->
                add(browsable("mix:${mix.n}", mix.title, subtitle = mix.artists.take(3).joinToString(", ").ifBlank { "Микс дня" }))
            }
            home()?.newReleases.orEmpty().take(NEW_RELEASES).forEach { add(release(it)) }
        }
        LIBRARY -> buildList {
            add(liked())
            library.playlists.value.forEach { p ->
                add(browsable("playlist:${p.id}", p.title, subtitle = "Плейлист", artwork = artwork(Cover.single(p.coverUrl))))
            }
            library.albums.value.forEach { add(release(it)) }
        }
        RECENT -> recentTracks().map { playable(it, RECENT) }
        DOWNLOADS -> downloadedTracks().map { playable(it, DOWNLOADS) }
        else -> if (isContext(parentId)) runCatching { catalog.contextTracks(parentId) }.getOrDefault(emptyList()).filter { it.playable }.map { playable(it, parentId) } else emptyList()
    }

    /** Голосовой и текстовый поиск в машине: треки; без сети — среди скачанного. */
    suspend fun search(query: String): List<MediaItem> {
        val q = query.trim()
        return searchTracks(q).map { playable(it, "$SEARCH$q") }
    }

    private suspend fun searchTracks(q: String): List<Track> {
        if (q.isEmpty()) return emptyList()
        val online = apiCall { api.search(q, limit = 30).tracks }.getOrNull()
        val tracks = (online ?: runCatching { offlineCatalog.search(q).tracks }.getOrNull()).orEmpty().map { it.toDomain() }
        // Без сети играет только скачанное — его и предлагаем.
        return tracks.filter(offline::isAvailable)
    }

    /** Что играть по выбранному элементу: подборка и с какого трека. */
    suspend fun resolve(mediaId: String): Selection? {
        if (!mediaId.startsWith(TRACK)) return null
        val body = mediaId.removePrefix(TRACK)
        val trackId = body.substringBefore('|').toLongOrNull() ?: return null
        return resolveContext(body.substringAfter('|', ""), trackId)
    }

    /** Подборка по ключу — с трека [trackId] или с первого. */
    suspend fun resolveContext(key: String, trackId: Long? = null): Selection? {
        val tracks = when {
            key == DOWNLOADS -> downloadedTracks()
            key == RECENT -> recentTracks()
            key.startsWith(SEARCH) -> searchTracks(key.removePrefix(SEARCH))
            isContext(key) -> runCatching { catalog.contextTracks(key) }.getOrDefault(emptyList())
            else -> emptyList()
        }.filter { it.playable }
        if (tracks.isEmpty()) return null
        val start = tracks.firstOrNull { it.id == trackId } ?: tracks.first()
        return Selection(tracks, start, contextName(key), if (key.startsWith(SEARCH)) "" else key)
    }

    /** «Включи …» голосом: лучший трек поиска, дальше — остальные результаты. */
    suspend fun resolveSearch(query: String): Selection? {
        val tracks = searchTracks(query.trim())
        val start = tracks.firstOrNull() ?: return null
        return Selection(tracks, start, "Поиск", "")
    }

    /** Элемент для onGetItem: трек по его id в дереве или раздел. */
    suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT -> root()
        mediaId.startsWith(TRACK) -> resolve(mediaId)?.let { playable(it.start, mediaId.substringAfter('|', "")) }
        else -> childrenOf(ROOT).firstOrNull { it.mediaId == mediaId }
    }

    /** Прошлое воспроизведение — системному «Продолжить» и машине после перезапуска. */
    fun resumeItem(track: Track): MediaItem = playable(track, "").buildUpon().setMediaId(RESUME).build()

    data class Selection(val tracks: List<Track>, val start: Track, val name: String, val key: String)

    private fun liked(): MediaItem = browsable(LIKED, "Любимые треки", subtitle = "Плейлист", artwork = CoverProvider.likedUri())

    private fun release(r: ReleaseDto): MediaItem =
        browsable("release:${r.slug}", r.title, subtitle = r.mainArtists().joinToString(", ") { it.name }, artwork = artwork(Cover.of(r.cover)))

    /** Главная из кэша (или сети) — её же показывает приложение. */
    private suspend fun home(): HomeDto? = runCatching { catalog.home().first { it !is Loadable.Loading }.dataOrNull }.getOrNull()

    private suspend fun recentTracks(): List<Track> = home()?.recentlyPlayed.orEmpty().map { it.toDomain() }.filter(offline::isAvailable)

    private suspend fun downloadedTracks(): List<Track> {
        val ids = offline.downloaded.value
        if (ids.isEmpty()) return emptyList()
        return runCatching { offlineCatalog.tracks(ids).map { it.toDomain() } }.getOrDefault(emptyList()).sortedBy { it.title.lowercase() }
    }

    private fun contextName(key: String): String = when {
        key == LIKED -> "Любимые треки"
        key == DOWNLOADS -> "Скачанное"
        key == RECENT -> "Недавно слушали"
        key.startsWith("playlist:") -> library.playlists.value.firstOrNull { "playlist:${it.id}" == key }?.title ?: "Плейлист"
        key.startsWith("release:") -> library.albums.value.firstOrNull { "release:${it.slug}" == key }?.title ?: "Альбом"
        key.startsWith("mix:") -> "Микс дня"
        key.startsWith(SEARCH) -> "Поиск"
        else -> ""
    }

    private fun isContext(key: String) = key == LIKED || key.startsWith("playlist:") || key.startsWith("release:") || key.startsWith("mix:")

    private fun browsable(id: String, title: String, subtitle: String? = null, artwork: Uri? = null, grid: Boolean = false): MediaItem {
        val extras = Bundle().apply {
            // Подборки — плитками, треки внутри — списком.
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, if (grid) MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM else MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    fun playable(track: Track, contextKey: String): MediaItem = MediaItem.Builder()
        .setMediaId("$TRACK${track.id}|$contextKey")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artistNames)
                .setAlbumTitle(track.release?.title)
                .setArtworkUri(artwork(track.cover))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    /** Машина не ходит в интернет за картинками — отдаём их своим ContentProvider'ом. */
    private fun artwork(cover: Cover?): Uri? = cover?.url(300)?.let { CoverProvider.uriFor(context, it) }

    companion object {
        const val ROOT = "root"
        const val HOME = "home"
        const val LIBRARY = "library"
        const val DOWNLOADS = "downloads"
        const val LIKED = "liked"
        const val RECENT = "recent"
        const val TRACK = "t:"
        const val SEARCH = "search:"

        /** Элемент «продолжить с того же места» (возобновление воспроизведения). */
        const val RESUME = "resume"

        val AUTHORITY = "${BuildConfig.APPLICATION_ID}.covers"

        private const val NEW_RELEASES = 8
    }
}
