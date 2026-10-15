package ru.nepalimsya.sukify.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /me/discord: привязанный аккаунт (null — не привязан) и настроен ли Discord на сервере. */
@Serializable
data class DiscordEnvelope(val data: DiscordDto? = null, val configured: Boolean = false)

@Serializable
data class DiscordDto(
    @SerialName("display_name") val displayName: String? = null,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("presence_enabled") val presenceEnabled: Boolean = true,
    @SerialName("show_when_paused") val showWhenPaused: Boolean = false,
    @SerialName("show_button") val showButton: Boolean = true,
    @SerialName("show_cover") val showCover: Boolean = true,
)

@Serializable
data class UrlDto(val url: String)

/** Привязанные приложения: компьютеры с компаньоном и телефоны. */
@Serializable
data class DesktopDeviceDto(
    val id: Long,
    val name: String,
    val platform: String? = null,
    @SerialName("approved_at") val approvedAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

/** GET /app/android — свежая сборка приложения (latest.json в S3). */
@Serializable
data class AppReleaseDto(
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    @SerialName("apk_url") val apkUrl: String,
    val sha256: String? = null,
    val size: Long? = null,
    val notes: String? = null,
    @SerialName("released_at") val releasedAt: String? = null,
)

@Serializable
data class AppReleaseEnvelope(val data: AppReleaseDto? = null)

/** GET /me/stats — последние 30 дней в Sukify. */
@Serializable
data class StatsDto(
    @SerialName("total_plays") val totalPlays: Long = 0,
    val minutes: Long = 0,
    @SerialName("top_tracks") val topTracks: List<StatTrackDto> = emptyList(),
    @SerialName("top_artists") val topArtists: List<StatArtistDto> = emptyList(),
)

@Serializable
data class StatTrackDto(val plays: Long = 0, val track: TrackDto)

@Serializable
data class StatArtistDto(val plays: Long = 0, val artist: ArtistDto)

/** GET /me/spotify-stats — история из архива Spotify. */
@Serializable
data class SpotifyStatsDto(
    @SerialName("has_data") val hasData: Boolean = false,
    val year: Int? = null,
    val years: List<SpotifyYearDto> = emptyList(),
    val totals: SpotifyTotalsDto? = null,
    @SerialName("top_artists") val topArtists: List<SpotifyArtistStatDto> = emptyList(),
    @SerialName("top_tracks") val topTracks: List<SpotifyTrackStatDto> = emptyList(),
    @SerialName("by_hour") val byHour: List<StatBucketDto> = emptyList(),
    @SerialName("by_weekday") val byWeekday: List<StatBucketDto> = emptyList(),
    @SerialName("by_month") val byMonth: List<StatBucketDto> = emptyList(),
    val platforms: List<PlatformStatDto> = emptyList(),
)

@Serializable
data class SpotifyYearDto(val year: Int, val plays: Long = 0, val ms: Long = 0)

@Serializable
data class SpotifyTotalsDto(
    val plays: Long = 0,
    val ms: Long = 0,
    val tracks: Long = 0,
    val artists: Long = 0,
    val skipped: Long = 0,
    val shuffled: Long = 0,
    @SerialName("full_plays") val fullPlays: Long = 0,
)

@Serializable
data class SpotifyArtistStatDto(
    val name: String,
    val plays: Long = 0,
    val ms: Long = 0,
    val slug: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class SpotifyTrackStatDto(
    val title: String,
    val artist: String = "",
    val plays: Long = 0,
    val ms: Long = 0,
    @SerialName("track_id") val trackId: Long? = null,
    val cover: List<CoverDto>? = null,
)

@Serializable
data class StatBucketDto(val bucket: Int, val plays: Long = 0, val ms: Long = 0)

@Serializable
data class PlatformStatDto(val platform: String, val plays: Long = 0, val ms: Long = 0)

/** Разбор архива Spotify (ImportController::payload). */
@Serializable
data class ImportJobDto(
    val id: Long,
    val status: String = "pending",
    val stage: String? = null,
    val progress: Int = 0,
    @SerialName("enrich_total") val enrichTotal: Long = 0,
    @SerialName("enrich_done") val enrichDone: Long = 0,
    @SerialName("original_name") val originalName: String? = null,
    val summary: ImportSummaryDto? = null,
    val error: String? = null,
) {
    val running: Boolean get() = status in setOf("pending", "parsing", "enriching")
}

@Serializable
data class ImportSummaryDto(
    val library: ImportLibraryDto? = null,
    val history: ImportHistoryDto? = null,
    /** Пояснение сервера: «Не нашёл внутри знакомых файлов Spotify» и т. п. */
    val note: String? = null,
)

@Serializable
data class ImportLibraryDto(
    @SerialName("liked_tracks") val likedTracks: Long = 0,
    val playlists: Long = 0,
    @SerialName("tracks_created") val tracksCreated: Long = 0,
    @SerialName("artists_created") val artistsCreated: Long = 0,
    @SerialName("releases_created") val releasesCreated: Long = 0,
    @SerialName("followed_artists") val followedArtists: Long = 0,
)

@Serializable
data class ImportHistoryDto(
    val plays: Long = 0,
    val ms: Long = 0,
    val from: String? = null,
    val to: String? = null,
    val duplicates: Long = 0,
)

/** Ответ на лайки из Exportify-CSV. */
@Serializable
data class LikedImportDto(val added: Long = 0, val already: Long = 0, val total: Long = 0)
