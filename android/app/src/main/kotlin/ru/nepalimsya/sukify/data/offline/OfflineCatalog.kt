package ru.nepalimsya.sukify.data.offline

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.data.model.ColorsDto
import ru.nepalimsya.sukify.data.model.CoverDto
import ru.nepalimsya.sukify.data.model.PlaylistDto
import ru.nepalimsya.sukify.data.model.ReleaseDto
import ru.nepalimsya.sukify.data.model.SearchDto
import ru.nepalimsya.sukify.data.model.TrackDto
import ru.nepalimsya.sukify.data.model.UserDto

/**
 * Медиатека из манифеста в том же виде, что отдаёт API: экраны и плеер
 * работают с ней как с обычным ответом сервера — без сети это всё, что есть.
 */
@Singleton
class OfflineCatalog @Inject constructor(
    private val dao: OfflineCatalogDao,
    private val json: Json,
) {
    @Volatile
    private var cachedUrls: ManifestUrls? = null

    suspend fun urls(): ManifestUrls? {
        cachedUrls?.let { return it }
        val raw = dao.meta(META_URLS)?.value ?: return null
        return runCatching { json.decodeFromString(ManifestUrls.serializer(), raw) }.getOrNull()?.also { cachedUrls = it }
    }

    suspend fun saveUrls(urls: ManifestUrls) {
        cachedUrls = urls
        dao.putMeta(OfflineMetaEntity(META_URLS, json.encodeToString(ManifestUrls.serializer(), urls)))
    }

    fun forgetUrls() {
        cachedUrls = null
    }

    /** Треки подборки (liked, playlist:12, release:5…) или null, если её нет в манифесте. */
    suspend fun sourceTracks(key: String): List<TrackDto>? {
        val rows = dao.sourceTracks(key)
        if (rows.isEmpty() && dao.sources().none { it.key == key }) return null
        val urls = urls() ?: return null
        val releases = dao.releasesByIds(rows.mapNotNull { it.releaseId }.distinct()).associateBy { it.id }
        val artists = dao.artistsByIds(rows.flatMap { it.artistIds.ids() }.distinct()).associateBy { it.id }
        return rows.map { row -> row.toDto(urls, releases, artists) }
    }

    /** Треки по id (в любом порядке) — для восстановления очереди без сети. */
    suspend fun tracks(ids: Collection<Long>): List<TrackDto> {
        if (ids.isEmpty()) return emptyList()
        val urls = urls() ?: return emptyList()
        val rows = ids.chunked(500).flatMap { dao.tracksByIds(it) }.map { it.toRow() }
        val releases = dao.releasesByIds(rows.mapNotNull { it.releaseId }.distinct()).associateBy { it.id }
        val artists = dao.artistsByIds(rows.flatMap { it.artistIds.ids() }.distinct()).associateBy { it.id }
        return rows.map { it.toDto(urls, releases, artists) }
    }

    /**
     * Поиск без сети — по копии медиатеки. В SQLite LIKE не умеет регистр
     * кириллицы, поэтому фильтруем в памяти: несколько тысяч строк — миллисекунды.
     */
    suspend fun search(query: String, limit: Int = 30): SearchDto {
        val q = query.trim()
        val urls = urls() ?: return SearchDto()
        if (q.isEmpty()) return SearchDto()
        val artists = dao.artists()
        val matchedArtists = artists.filter { it.name.contains(q, ignoreCase = true) }
        val artistIds = matchedArtists.mapTo(HashSet()) { it.id }
        val rows = dao.tracks()
            .filter { t -> t.title.contains(q, ignoreCase = true) || t.artistIds.ids().any { it in artistIds } }
            .take(limit)
            .map { it.toRow() }
        val releases = dao.releases()
        val byArtist = artists.associateBy { it.id }
        val byRelease = releases.associateBy { it.id }
        return SearchDto(
            tracks = rows.map { it.toDto(urls, byRelease, byArtist) },
            releases = releases.filter { it.title.contains(q, ignoreCase = true) }.take(10).map { it.toDto(urls, byArtist) },
            artists = matchedArtists.take(10).map { it.toDto() },
        )
    }

    /** Плейлист из манифеста — шапка и состав. */
    suspend fun playlist(id: Long): PlaylistDto? {
        val source = dao.sources().firstOrNull { it.key == "playlist:$id" } ?: return null
        val tracks = sourceTracks(source.key) ?: return null
        return PlaylistDto(
            id = id,
            title = source.title.orEmpty(),
            description = source.description,
            coverUrl = source.coverUrl,
            isPublic = source.isPublic,
            isOwner = source.isOwner,
            isCollaborative = source.isCollaborative,
            owner = source.ownerName?.let { UserDto(id = 0, name = it, username = source.ownerUsername) },
            tracksCount = tracks.size,
            tracks = tracks,
        )
    }

    /** Альбом из манифеста (если он скачан или лежит в медиатеке). */
    suspend fun release(slug: String): ReleaseDto? {
        val release = dao.releases().firstOrNull { it.slug == slug } ?: return null
        val urls = urls() ?: return null
        val artists = dao.artistsByIds(listOfNotNull(release.artistId)).associateBy { it.id }
        val tracks = sourceTracks("release:${release.id}")
            ?: dao.tracks().filter { it.releaseId == release.id }.sortedBy { it.trackNumber ?: 0 }
                .map { it.toRow() }
                .let { rows ->
                    val trackArtists = dao.artistsByIds(rows.flatMap { it.artistIds.ids() }.distinct()).associateBy { it.id }
                    rows.map { it.toDto(urls, mapOf(release.id to release), trackArtists) }
                }
        return release.toDto(urls, artists).copy(tracks = tracks, tracksCount = tracks.size)
    }

    private fun SourceTrackRow.toDto(
        urls: ManifestUrls,
        releases: Map<Long, OfflineReleaseEntity>,
        artists: Map<Long, OfflineArtistEntity>,
    ): TrackDto {
        val release = releaseId?.let(releases::get)
        val cover = if (ownCover) {
            urls.coverSizes.map { CoverDto(it, urls.trackCover(id, it)) }
        } else {
            release?.coverList(urls)
        }
        return TrackDto(
            id = id,
            title = title,
            trackNumber = trackNumber,
            durationMs = durationMs,
            loudnessLufs = lufs,
            processingStatus = "ready",
            streamUrl = audioRev?.let { "${urls.audio(id)}?v=$it" },
            audioRev = audioRev,
            cover = cover,
            unofficial = unofficial,
            previewOnly = preview,
            artists = artistIds.ids().mapNotNull { artists[it]?.toDto() },
            release = release?.toDto(urls, emptyMap()),
            playlistItemId = itemId,
            addedAt = addedAt?.toString(),
            bpm = bpm?.toDouble(),
            camelot = camelot,
        )
    }

    private fun OfflineReleaseEntity.coverList(urls: ManifestUrls): List<CoverDto>? =
        coverV?.let { v -> urls.coverSizes.map { CoverDto(it, urls.releaseCover(id, it, v)) } }

    private fun OfflineReleaseEntity.toDto(urls: ManifestUrls, artists: Map<Long, OfflineArtistEntity>) = ReleaseDto(
        id = id,
        title = title,
        slug = slug,
        type = type,
        year = year,
        cover = coverList(urls),
        colors = bg?.let { ColorsDto(background = it) },
        artist = artistId?.let(artists::get)?.toDto(),
    )

    private fun OfflineArtistEntity.toDto() = ArtistDto(
        id = id,
        name = name,
        slug = slug,
        avatarUrl = avatarUrl,
        bannerUrl = bannerUrl,
        colors = bg?.let { ColorsDto(background = it) },
    )

    private fun OfflineTrackEntity.toRow() = SourceTrackRow(
        position = trackNumber ?: 0,
        itemId = null,
        addedAt = null,
        id = id,
        title = title,
        releaseId = releaseId,
        trackNumber = trackNumber,
        durationMs = durationMs,
        artistIds = artistIds,
        lufs = lufs,
        bpm = bpm,
        camelot = camelot,
        preview = preview,
        unofficial = unofficial,
        ownCover = ownCover,
        audioRev = audioRev,
        audioSize = audioSize,
    )

    companion object {
        const val META_URLS = "urls"

        fun String.ids(): List<Long> = if (isEmpty()) emptyList() else split(',').mapNotNull { it.toLongOrNull() }
    }
}
