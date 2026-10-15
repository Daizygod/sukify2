package ru.nepalimsya.sukify.data.offline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * GET /api/offline/manifest (OfflineController): вся медиатека и выбранные
 * для загрузки подборки одним компактным JSON. Ссылки на файлы — шаблонами
 * в шапке, чтобы не повторять один и тот же адрес тысячи раз.
 */

@Serializable
data class ManifestDto(
    val version: Int = 1,
    val urls: ManifestUrls,
    val library: ManifestLibrary = ManifestLibrary(),
    val sources: List<ManifestSource> = emptyList(),
    /** Ключи из include, которых больше нет или к которым нет доступа. */
    val missing: List<String> = emptyList(),
    val releases: Map<Long, ManifestRelease> = emptyMap(),
    val artists: Map<Long, ManifestArtist> = emptyMap(),
    val tracks: List<ManifestTrack> = emptyList(),
)

@Serializable
data class ManifestUrls(
    /** …/audio/{id}/stream.mp3 */
    val audio: String,
    /** …/covers/{id}/{size}.webp?v={v} */
    @SerialName("release_cover") val releaseCover: String,
    /** …/track-covers/{id}/{size}.webp */
    @SerialName("track_cover") val trackCover: String,
    @SerialName("cover_sizes") val coverSizes: List<Int> = listOf(64, 160, 300, 640, 1000),
) {
    fun audio(trackId: Long): String = audio.replace("{id}", trackId.toString())

    fun releaseCover(releaseId: Long, size: Int, version: Long): String =
        releaseCover.replace("{id}", releaseId.toString()).replace("{size}", size.toString()).replace("{v}", version.toString())

    fun trackCover(trackId: Long, size: Int): String =
        trackCover.replace("{id}", trackId.toString()).replace("{size}", size.toString())
}

/** Что лежит в медиатеке пользователя — независимо от того, что выбрано для загрузки. */
@Serializable
data class ManifestLibrary(
    val playlists: List<Long> = emptyList(),
    val albums: List<Long> = emptyList(),
    val artists: List<Long> = emptyList(),
    val pins: List<String> = emptyList(),
)

/** Подборка: «Любимые», плейлист, альбом, дискография исполнителя или один трек. */
@Serializable
data class ManifestSource(
    val key: String,
    val type: String,
    val id: Long? = null,
    @SerialName("track_ids") val trackIds: List<Long> = emptyList(),
    @SerialName("item_ids") val itemIds: List<Long>? = null,
    @SerialName("added_at") val addedAt: List<Long>? = null,
    // Только у плейлистов:
    val title: String? = null,
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("is_public") val isPublic: Boolean? = null,
    @SerialName("is_owner") val isOwner: Boolean? = null,
    @SerialName("is_collaborative") val isCollaborative: Boolean? = null,
    @SerialName("mix_enabled") val mixEnabled: Boolean? = null,
    val owner: ManifestOwner? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

@Serializable
data class ManifestOwner(val id: Long, val name: String, val username: String? = null)

@Serializable
data class ManifestRelease(
    val title: String,
    val slug: String,
    val type: String? = null,
    val year: Int? = null,
    @SerialName("artist_id") val artistId: Long? = null,
    /** Версия обложки (null — обложки ещё нет). */
    @SerialName("cover_v") val coverV: Long? = null,
    val bg: String? = null,
    val fg: String? = null,
)

@Serializable
data class ManifestArtist(
    val name: String,
    val slug: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    val bg: String? = null,
    val fg: String? = null,
)

@Serializable
data class ManifestTrack(
    val id: Long,
    val title: String,
    @SerialName("release_id") val releaseId: Long? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("artist_ids") val artistIds: List<Long> = emptyList(),
    val lufs: Double? = null,
    val bpm: Double? = null,
    val camelot: String? = null,
    val preview: Boolean = false,
    val unofficial: Boolean = false,
    @SerialName("own_cover") val ownCover: Boolean = false,
    /** null — у трека нет звука, скачивать нечего. */
    val audio: ManifestAudio? = null,
)

@Serializable
data class ManifestAudio(val rev: String, val size: Long)
