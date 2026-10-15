package ru.nepalimsya.sukify.domain

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.data.model.CoverDto
import ru.nepalimsya.sukify.data.model.ReleaseDto
import ru.nepalimsya.sukify.data.model.TrackDto

/*
 * Модели, которыми живёт интерфейс и плеер. Неизменяемые (@Immutable) —
 * Compose пропускает перерисовку строк списка, если трек тот же.
 */

/** Набор рендишнов обложки: 64…1000 px. */
@Immutable
data class Cover(val urls: ImmutableList<Pair<Int, String>>) {
    /** Самый маленький рендишн не меньше minPx (или самый большой, если таких нет). */
    fun url(minPx: Int): String? =
        urls.firstOrNull { it.first >= minPx }?.second ?: urls.lastOrNull()?.second

    companion object {
        fun of(dtos: List<CoverDto>?): Cover? {
            val list = dtos.orEmpty()
                .mapNotNull { c -> c.url?.let { c.size to it } }
                .sortedBy { it.first }
            return if (list.isEmpty()) null else Cover(list.toImmutableList())
        }

        /** Одна картинка без рендишнов (обложка плейлиста, аватар). */
        fun single(url: String?): Cover? = url?.let { Cover(persistentListOf(1000 to it)) }
    }
}

@Immutable
data class ArtistRef(val id: Long, val name: String, val slug: String)

@Immutable
data class ReleaseRef(
    val id: Long,
    val title: String,
    val slug: String,
    /** Цвет фона из обложки (#rrggbb) — для градиентов и мини-плеера. */
    val color: String? = null,
)

@Immutable
data class Track(
    val id: Long,
    val title: String,
    val durationMs: Long,
    val artists: ImmutableList<ArtistRef>,
    val release: ReleaseRef?,
    val cover: Cover?,
    /** null — у трека нет звука (ещё не залит или не нашёлся в Deezer). */
    val streamUrl: String?,
    val audioRev: String?,
    val loudnessLufs: Double?,
    val previewOnly: Boolean = false,
    val unofficial: Boolean = false,
    val bpm: Int? = null,
    val camelot: String? = null,
) {
    val playable: Boolean get() = streamUrl != null

    val artistNames: String get() = artists.joinToString(", ") { it.name }

    /** Ключ кэша и загрузок: меняется вместе с файлом, а не с адресом. */
    val cacheKey: String get() = "t$id:${audioRev ?: "0"}"
}

fun ArtistDto.toRef() = ArtistRef(id, name, slug)

fun ReleaseDto.toRef() = ReleaseRef(id, title, slug, colors?.background)

/** Трек из ответа API. Обложка трека — своя или релиза (как в Track::coverUrls). */
fun TrackDto.toDomain(fallbackRelease: ReleaseDto? = null): Track {
    val rel = release ?: fallbackRelease
    return Track(
        id = id,
        title = title,
        durationMs = durationMs ?: 0,
        artists = artists.orEmpty().map { it.toRef() }.toImmutableList(),
        release = rel?.toRef(),
        cover = Cover.of(cover) ?: Cover.of(rel?.cover),
        streamUrl = streamUrl,
        audioRev = audioRev,
        loudnessLufs = loudnessLufs,
        previewOnly = previewOnly,
        unofficial = unofficial,
        bpm = bpm?.toInt(),
        camelot = camelot,
    )
}
