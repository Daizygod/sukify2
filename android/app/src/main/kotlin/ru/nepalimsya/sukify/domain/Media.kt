package ru.nepalimsya.sukify.domain

import androidx.compose.runtime.Immutable
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.data.model.DailyMixDto
import ru.nepalimsya.sukify.data.model.PlaylistDto
import ru.nepalimsya.sukify.data.model.ReleaseDto
import ru.nepalimsya.sukify.data.model.UserDto

/**
 * Ключи подборок — те же, что у веба (usePlayContext): по ним кнопки ▶
 * понимают, что играет именно их подборка, и их же получают пульты Connect.
 */
object ContextKeys {
    const val LIKED = "liked"

    fun release(slug: String) = "release:$slug"

    fun playlist(id: Long) = "playlist:$id"

    fun mix(n: Int) = "mix:$n"

    fun artist(slug: String) = "artist:$slug"

    fun artistLiked(slug: String) = "artist-liked:$slug"
}

enum class MediaKind { Release, Playlist, Artist, Mix, User, Liked }

/**
 * Карточка чего угодно — релиза, плейлиста, исполнителя, микса, человека:
 * для полок главной, сеток поиска и строк медиатеки.
 */
@Immutable
data class MediaItem(
    val kind: MediaKind,
    /** slug релиза/исполнителя, id плейлиста, номер микса, username. */
    val ref: String,
    val title: String,
    val subtitle: String,
    val cover: Cover?,
    val round: Boolean = false,
    /** Цвет плитки (миксы) или фона шапки. */
    val color: String? = null,
    /** Ключ подборки для ▶; null — у карточки нет своей кнопки. */
    val contextKey: String? = null,
    val id: Long = 0,
    /** Плашка на обложке (жанр микса). */
    val tag: String? = null,
) {
    /** Уникален и для карточек треков с одного альбома (у них общий ref, но свой id). */
    val key: String get() = "${kind.name}:$ref:$id"
}

private val releaseKinds = mapOf(
    "album" to "Альбом",
    "single" to "Сингл",
    "ep" to "Мини-альбом",
    "compilation" to "Сборник",
)

fun releaseKindLabel(type: String?): String = releaseKinds[type] ?: "Альбом"

fun ArtistDto.imageUrl(): String? = avatarUrl ?: bannerUrl

fun ReleaseDto.mainArtists(): List<ArtistDto> = artists?.takeIf { it.isNotEmpty() } ?: listOfNotNull(artist)

/** Карточка релиза: подпись «2024 · Исполнитель» (как MediaCard в вебе). */
fun ReleaseDto.toMedia(subtitle: String = listOfNotNull(year?.toString(), artist?.name).joinToString(" · ")) = MediaItem(
    kind = MediaKind.Release,
    ref = slug,
    title = title,
    subtitle = subtitle,
    cover = Cover.of(cover),
    color = colors?.background,
    contextKey = ContextKeys.release(slug),
    id = id,
)

fun PlaylistDto.toMedia(subtitle: String = "Автор: ${owner?.name.orEmpty()}") = MediaItem(
    kind = MediaKind.Playlist,
    ref = id.toString(),
    title = title,
    subtitle = subtitle,
    cover = Cover.single(coverUrl),
    contextKey = ContextKeys.playlist(id),
    id = id,
)

fun ArtistDto.toMedia(subtitle: String = "Исполнитель") = MediaItem(
    kind = MediaKind.Artist,
    ref = slug,
    title = name,
    subtitle = subtitle,
    cover = Cover.single(imageUrl()),
    round = true,
    color = colors?.background,
    contextKey = ContextKeys.artist(slug),
    id = id,
)

fun DailyMixDto.toMedia() = MediaItem(
    kind = MediaKind.Mix,
    ref = n.toString(),
    title = title,
    subtitle = if (artists.isEmpty()) genre.orEmpty() else artists.joinToString(", ") + " и не только",
    cover = null,
    color = color,
    contextKey = ContextKeys.mix(n),
    id = n.toLong(),
    tag = genre,
)

fun UserDto.toMedia() = MediaItem(
    kind = MediaKind.User,
    ref = username ?: id.toString(),
    title = name,
    subtitle = username?.let { "@$it" } ?: "Пользователь",
    cover = Cover.single(avatarUrl),
    round = true,
    id = id,
)
