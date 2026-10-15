package ru.nepalimsya.sukify.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {

    private val lrc = """
        [ar:Artist]
        [00:12.50]Первая строка
        [00:15.00][01:15.00]Припев
        [00:20.25]
        [00:18.00]Вторая строка
    """.trimIndent()

    @Test
    fun parsesAndSortsTimestamps() {
        val lyrics = Lyrics.parse(lrc, null)
        assertEquals(listOf(12_500L, 15_000L, 18_000L, 20_250L, 75_000L), lyrics.synced.map { it.ms })
        assertEquals("Припев", lyrics.synced.last().text)
        assertEquals("♪", lyrics.displayLines[3])
    }

    @Test
    fun activeLineFollowsPosition() {
        val lyrics = Lyrics.parse(lrc, null)
        assertEquals(-1, lyrics.activeIndex(0))
        assertEquals(0, lyrics.activeIndex(12_500))
        assertEquals(1, lyrics.activeIndex(16_000))
        assertEquals(3, lyrics.activeIndex(60_000))
        assertEquals(4, lyrics.activeIndex(200_000))
    }

    @Test
    fun plainOnly() {
        val lyrics = Lyrics.parse(null, "раз\nдва")
        assertTrue(lyrics.hasAny)
        assertEquals(listOf("раз", "два"), lyrics.displayLines)
        assertEquals(-1, lyrics.activeIndex(10_000))
        assertFalse(Lyrics.parse("", " ").hasAny)
    }
}
