package ru.nepalimsya.sukify.screenshots

import kotlinx.collections.immutable.persistentListOf
import ru.nepalimsya.sukify.domain.ArtistRef
import ru.nepalimsya.sukify.domain.Lyrics
import ru.nepalimsya.sukify.domain.ReleaseRef
import ru.nepalimsya.sukify.domain.Track

/** Тестовые треки для скриншотов — те же, что в сидере бэкенда. */
object Fixtures {
    private val metro = ArtistRef(1, "Metro Boomin", "metro-boomin")
    private val luna = ArtistRef(2, "Luna Waves", "luna-waves")
    private val heroes = ReleaseRef(1, "HEROES & VILLAINS", "heroes-villains", "#5a1e1e")
    private val midnight = ReleaseRef(2, "Midnight Currents", "midnight-currents", "#12303a")

    fun track(id: Long, title: String, artist: ArtistRef = metro, release: ReleaseRef = heroes, preview: Boolean = false) = Track(
        id = id,
        title = title,
        durationMs = 187_000 + id * 7_000,
        artists = persistentListOf(artist),
        release = release,
        cover = null,
        streamUrl = "https://s3/audio/$id/stream.mp3",
        audioRev = "rev$id",
        loudnessLufs = -9.5,
        previewOnly = preview,
    )

    val tracks = listOf(
        track(1, "On Time"),
        track(2, "Superhero (Heroes & Villains)"),
        track(3, "Too Many Nights"),
        track(4, "Неоновый прилив", luna, midnight),
        track(5, "Undertow", luna, midnight, preview = true),
        track(6, "Harbor Lights", luna, midnight),
    )

    val lyrics = Lyrics.parse(
        """
        [00:05.00]Я вышел в ночь, где город не спит
        [00:09.00]Неоновый свет по стеклу бежит
        [00:13.00]И каждый шаг — как удар в груди
        [00:17.00]Всё, что было, оставь позади
        [00:21.00]♪
        [00:25.00]Мы снова здесь, под шум волны
        """.trimIndent(),
        null,
    )
}
