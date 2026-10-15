package ru.nepalimsya.sukify.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Ответы API в том виде, как их отдают ресурсы Laravel (app/Http/Resources).
 * Связи, которые сервер не подгрузил (whenLoaded), в JSON просто отсутствуют —
 * поэтому почти всё здесь nullable.
 */

@Serializable
data class CoverDto(val size: Int, val url: String? = null)

@Serializable
data class ColorsDto(val background: String? = null, val text: String? = null)

@Serializable
data class ArtistDto(
    val id: Long,
    val name: String,
    val slug: String,
    val bio: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    val colors: ColorsDto? = null,
    @SerialName("monthly_listeners") val monthlyListeners: Long? = null,
    val genre: String? = null,
    val role: String? = null,
    @SerialName("is_followed") val isFollowed: Boolean? = null,
    val releases: List<ReleaseDto>? = null,
    @SerialName("appears_on") val appearsOn: List<ReleaseDto>? = null,
)

@Serializable
data class ReleaseDto(
    val id: Long,
    val title: String,
    val slug: String,
    val type: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val year: Int? = null,
    val cover: List<CoverDto>? = null,
    @SerialName("cover_original") val coverOriginal: String? = null,
    val colors: ColorsDto? = null,
    val artist: ArtistDto? = null,
    val artists: List<ArtistDto>? = null,
    val tracks: List<TrackDto>? = null,
    @SerialName("tracks_count") val tracksCount: Int? = null,
    @SerialName("is_liked") val isLiked: Boolean? = null,
)

@Serializable
data class TrackDto(
    val id: Long,
    val title: String,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("loudness_lufs") val loudnessLufs: Double? = null,
    @SerialName("processing_status") val processingStatus: String? = null,
    @SerialName("stream_url") val streamUrl: String? = null,
    @SerialName("audio_rev") val audioRev: String? = null,
    val cover: List<CoverDto>? = null,
    @SerialName("plays_count") val playsCount: Long? = null,
    @SerialName("likes_count") val likesCount: Long? = null,
    val unofficial: Boolean = false,
    @SerialName("preview_only") val previewOnly: Boolean = false,
    val artists: List<ArtistDto>? = null,
    val release: ReleaseDto? = null,
    @SerialName("playlist_position") val playlistPosition: Int? = null,
    @SerialName("playlist_item_id") val playlistItemId: Long? = null,
    @SerialName("added_at") val addedAt: String? = null,
    @SerialName("liked_at") val likedAt: String? = null,
    @SerialName("is_liked") val isLiked: Boolean? = null,
    val bpm: Double? = null,
    val camelot: String? = null,
    @SerialName("musical_key") val musicalKey: String? = null,
    @SerialName("musical_scale") val musicalScale: String? = null,
)

@Serializable
data class PlaylistDto(
    val id: Long,
    val title: String,
    val description: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("is_public") val isPublic: Boolean = true,
    @SerialName("is_owner") val isOwner: Boolean = false,
    @SerialName("is_collaborator") val isCollaborator: Boolean = false,
    @SerialName("is_collaborative") val isCollaborative: Boolean = false,
    val owner: UserDto? = null,
    val collaborators: List<UserDto>? = null,
    @SerialName("tracks_count") val tracksCount: Int? = null,
    val tracks: List<TrackDto>? = null,
    @SerialName("mix_enabled") val mixEnabled: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** GET /home */
@Serializable
data class HomeDto(
    @SerialName("recently_played") val recentlyPlayed: List<TrackDto> = emptyList(),
    @SerialName("popular_tracks") val popularTracks: List<TrackDto> = emptyList(),
    @SerialName("new_releases") val newReleases: List<ReleaseDto> = emptyList(),
    @SerialName("recently_added") val recentlyAdded: List<ReleaseDto> = emptyList(),
)

/** GET /playback-settings */
@Serializable
data class PlaybackSettingsDto(
    @SerialName("target_loudness_lufs") val targetLoudnessLufs: Double = -14.0,
    @SerialName("default_crossfade_seconds") val defaultCrossfadeSeconds: Int = 0,
    @SerialName("smart_shuffle_enabled") val smartShuffleEnabled: Boolean = false,
)

/** GET /tracks/{id}/lyrics */
@Serializable
data class LyricsDto(
    val found: Boolean = false,
    val synced: String? = null,
    val plain: String? = null,
)

/** GET /transitions?from&to → TransitionResource (data может быть null). */
@Serializable
data class TransitionDto(
    val id: Long,
    @SerialName("from_track_id") val fromTrackId: Long,
    @SerialName("to_track_id") val toTrackId: Long,
    @SerialName("fade_out_start_ms") val fadeOutStartMs: Long,
    @SerialName("fade_out_end_ms") val fadeOutEndMs: Long,
    @SerialName("fade_in_start_ms") val fadeInStartMs: Long,
    @SerialName("fade_in_full_volume_ms") val fadeInFullVolumeMs: Long,
    @SerialName("curve_type") val curveType: String = "equal_power",
    val preset: String? = null,
    @SerialName("volume_shape") val volumeShape: String? = null,
    @SerialName("eq_shape") val eqShape: String? = null,
    @SerialName("filter_shape") val filterShape: String? = null,
    val bars: Int? = null,
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("created_by_user_id") val createdByUserId: Long? = null,
    @SerialName("is_liked") val isLiked: Boolean? = null,
    @SerialName("is_preferred") val isPreferred: Boolean? = null,
    @SerialName("is_mine") val isMine: Boolean? = null,
)

@Serializable
data class NullableData<T>(val data: T? = null)

/** Пагинация Laravel: {data: [...], meta: {current_page, last_page, total}}. */
@Serializable
data class Page<T>(
    val data: List<T>,
    val meta: PageMeta? = null,
)

@Serializable
data class PageMeta(
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("last_page") val lastPage: Int = 1,
    val total: Int = 0,
)

/** GET /search?q= */
@Serializable
data class SearchDto(
    val tracks: List<TrackDto> = emptyList(),
    val releases: List<ReleaseDto> = emptyList(),
    val artists: List<ArtistDto> = emptyList(),
    val playlists: List<PlaylistDto> = emptyList(),
    val users: List<UserDto> = emptyList(),
)

/** GET /genres → [{genre, artists_count}] */
@Serializable
data class GenreDto(
    val genre: String,
    @SerialName("artists_count") val artistsCount: Int = 0,
)

/** GET /genres/{genre} */
@Serializable
data class GenreDetailDto(
    val genre: String,
    val artists: List<ArtistDto> = emptyList(),
    val releases: List<ReleaseDto> = emptyList(),
)

/** GET /mixes/daily → карточки «Создано для тебя». */
@Serializable
data class DailyMixDto(
    val n: Int,
    val title: String,
    val genre: String? = null,
    @SerialName("tracks_count") val tracksCount: Int = 0,
    val artists: List<String> = emptyList(),
    val color: String? = null,
)

/** GET /mixes/daily/{n} */
@Serializable
data class DailyMixDetailDto(
    val title: String,
    val genre: String? = null,
    val color: String? = null,
    val tracks: List<TrackDto> = emptyList(),
)

/** GET /users/{username} */
@Serializable
data class ProfileDto(
    val user: UserDto,
    val playlists: List<PlaylistDto> = emptyList(),
    @SerialName("public_playlists_count") val publicPlaylistsCount: Int = 0,
    @SerialName("followers_count") val followersCount: Int = 0,
    @SerialName("following_count") val followingCount: Int = 0,
    @SerialName("friends_count") val friendsCount: Int = 0,
    @SerialName("is_followed") val isFollowed: Boolean = false,
    @SerialName("follows_me") val followsMe: Boolean = false,
    @SerialName("is_friend") val isFriend: Boolean = false,
    @SerialName("mutual_friends") val mutualFriends: List<UserDto> = emptyList(),
    @SerialName("is_me") val isMe: Boolean = false,
)

/** GET /me/history → [{played_at, track}] */
@Serializable
data class HistoryItemDto(
    @SerialName("played_at") val playedAt: String,
    val track: TrackDto,
)

/** GET /library/pins → [{item_type: playlist|album|artist, item_id}] */
@Serializable
data class PinDto(
    @SerialName("item_type") val itemType: String,
    @SerialName("item_id") val itemId: Long,
)

/** GET /artists/{slug}/liked */
@Serializable
data class ArtistLikedDto(
    val tracks: List<TrackDto> = emptyList(),
    val releases: List<ReleaseDto> = emptyList(),
)

/** POST/PUT /playlists */
@Serializable
data class PlaylistRequest(
    val title: String? = null,
    val description: String? = null,
    @SerialName("is_public") val isPublic: Boolean? = null,
)

@Serializable
data class InviteDto(@SerialName("invite_token") val inviteToken: String)

/** POST /realtime/connection-token */
@Serializable
data class ConnectionTokenDto(
    val token: String,
    @SerialName("ws_url") val wsUrl: String,
    @SerialName("playback_channel") val playbackChannel: String,
)
