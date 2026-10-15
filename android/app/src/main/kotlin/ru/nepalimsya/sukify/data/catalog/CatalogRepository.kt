package ru.nepalimsya.sukify.data.catalog

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.data.cache.ApiCache
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.data.model.ArtistLikedDto
import ru.nepalimsya.sukify.data.model.DailyMixDetailDto
import ru.nepalimsya.sukify.data.model.DailyMixDto
import ru.nepalimsya.sukify.data.model.GenreDetailDto
import ru.nepalimsya.sukify.data.model.GenreDto
import ru.nepalimsya.sukify.data.model.HistoryItemDto
import ru.nepalimsya.sukify.data.model.HomeDto
import ru.nepalimsya.sukify.data.model.PlaylistDto
import ru.nepalimsya.sukify.data.model.ProfileDto
import ru.nepalimsya.sukify.data.model.ReleaseDto
import ru.nepalimsya.sukify.data.model.TrackDto
import ru.nepalimsya.sukify.data.offline.OfflineCatalog
import ru.nepalimsya.sukify.domain.ContextKeys
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.domain.toDomain

/**
 * Каталог для экранов: каждый поток сначала отдаёт прошлый ответ (если он
 * был), потом свежий — экран не мигает пустотой ни при «назад», ни при
 * холодном старте.
 */
@Singleton
class CatalogRepository @Inject constructor(
    private val api: SukifyApi,
    private val cache: ApiCache,
    private val offline: OfflineCatalog,
) {
    fun home(): Flow<Loadable<HomeDto>> = cache.stream("home", HomeDto.serializer()) { api.home() }

    fun dailyMixes(): Flow<Loadable<List<DailyMixDto>>> =
        cache.stream("mixes", ListSerializer(DailyMixDto.serializer())) { api.dailyMixes().data }

    fun dailyMix(n: Int): Flow<Loadable<DailyMixDetailDto>> =
        cache.stream("mix:$n", DailyMixDetailDto.serializer()) { api.dailyMix(n) }

    fun release(slug: String): Flow<Loadable<ReleaseDto>> =
        cache.stream("release:$slug", ReleaseDto.serializer(), fallback = { offline.release(slug) }) { api.release(slug).data }

    fun playlist(id: Long): Flow<Loadable<PlaylistDto>> =
        cache.stream("playlist:$id", PlaylistDto.serializer(), fallback = { offline.playlist(id) }) { api.playlist(id).data }

    fun artist(slug: String): Flow<Loadable<ArtistDto>> =
        cache.stream("artist:$slug", ArtistDto.serializer()) { api.artist(slug).data }

    fun artistTopTracks(slug: String): Flow<Loadable<List<TrackDto>>> =
        cache.stream("artist-top:$slug", ListSerializer(TrackDto.serializer())) { api.artistTopTracks(slug).data }

    fun artistLiked(slug: String): Flow<Loadable<ArtistLikedDto>> =
        cache.stream("artist-liked:$slug", ArtistLikedDto.serializer()) { api.artistLiked(slug) }

    fun genres(): Flow<Loadable<List<GenreDto>>> =
        cache.stream("genres", ListSerializer(GenreDto.serializer())) { api.genres().data }

    fun genre(name: String): Flow<Loadable<GenreDetailDto>> =
        cache.stream("genre:$name", GenreDetailDto.serializer()) { api.genre(name) }

    fun profile(username: String): Flow<Loadable<ProfileDto>> =
        cache.stream("user:$username", ProfileDto.serializer()) { api.profile(username) }

    fun history(): Flow<Loadable<List<HistoryItemDto>>> =
        cache.stream("history", ListSerializer(HistoryItemDto.serializer())) { api.history().data }

    fun notifications(): Flow<Loadable<List<ReleaseDto>>> =
        cache.stream("notifications", ListSerializer(ReleaseDto.serializer())) { api.notifications().data }

    /** «Любимые треки»: после импорта из Spotify их тысячи — тянем страницами по 200. */
    fun likedTracks(): Flow<Loadable<List<TrackDto>>> =
        cache.stream(KEY_LIKED, ListSerializer(TrackDto.serializer()), fallback = { offline.sourceTracks(ContextKeys.LIKED) }) { fetchAllLiked() }

    /** Свежий список любимых сразу в кэш — после лайка/дизлайка в другом месте. */
    suspend fun refreshLiked(): List<TrackDto> {
        val all = fetchAllLiked()
        cache.write(KEY_LIKED, ListSerializer(TrackDto.serializer()), all)
        return all
    }

    fun cachedLiked(): List<TrackDto>? = cache.peek(KEY_LIKED)

    private suspend fun fetchAllLiked(): List<TrackDto> {
        val all = ArrayList<TrackDto>()
        var page = 1
        while (true) {
            val response = api.likedTracks(page)
            all += response.data
            val last = response.meta?.lastPage ?: 1
            if (page >= last) break
            page++
        }
        return all
    }

    fun forget(key: String) = cache.forget(key)

    /** То, что уже лежит в памяти, — для мгновенного первого кадра экрана. */
    fun <T> peek(key: String): T? = cache.peek(key)

    /**
     * Треки подборки по её ключу — для ▶ на карточках и «Добавить в очередь»
     * из меню (entityTracks в ContextMenu.vue).
     */
    suspend fun contextTracks(key: String): List<Track> {
        val (type, ref) = key.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (type == ContextKeys.LIKED) {
            return (cachedLiked() ?: offline.sourceTracks(ContextKeys.LIKED) ?: refreshLiked()).map { it.toDomain() }
        }
        return try {
            when (type) {
                "release" -> api.release(ref).data.let { r -> r.tracks.orEmpty().map { it.toDomain(r) } }
                "playlist" -> api.playlist(ref.toLong()).data.tracks.orEmpty().map { it.toDomain() }
                "mix" -> api.dailyMix(ref.toInt()).tracks.map { it.toDomain() }
                "artist" -> api.artistTopTracks(ref).data.map { it.toDomain() }
                "artist-liked" -> api.artistLiked(ref).tracks.map { it.toDomain() }
                else -> emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Без сети — то, что есть в загрузках.
            val local = when (type) {
                "release" -> offline.release(ref)?.let { r -> r.tracks.orEmpty().map { it.toDomain(r) } }
                "playlist" -> ref.toLongOrNull()?.let { offline.playlist(it) }?.tracks?.map { it.toDomain() }
                else -> null
            }
            local ?: throw e
        }
    }

    private companion object {
        const val KEY_LIKED = "liked-tracks"
    }
}
