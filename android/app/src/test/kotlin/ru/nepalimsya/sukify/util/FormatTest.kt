package ru.nepalimsya.sukify.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Те же правила, что у frontend/src/lib/format.js. */
class FormatTest {

    @Test
    fun durations() {
        assertEquals("0:00", Format.duration(null))
        assertEquals("0:00", Format.duration(0))
        assertEquals("3:07", Format.duration(187_000))
        assertEquals("61:01", Format.duration(3_661_000))
    }

    @Test
    fun russianPlurals() {
        val forms = listOf(1L to "трек", 2L to "трека", 4L to "трека", 5L to "треков", 11L to "треков",
            12L to "треков", 21L to "трек", 22L to "трека", 111L to "треков", 101L to "трек", 0L to "треков")
        for ((n, expected) in forms) {
            assertEquals("n=$n", expected, Format.plural(n, "трек", "трека", "треков"))
        }
    }

    @Test
    fun trackCountUsesGroupedNumbers() {
        // Разделитель разрядов в ru — неразрывный пробел (обычный или узкий, зависит от JDK).
        assertEquals("3 109 треков", Format.trackCount(3109).replace(' ', ' ').replace(' ', ' '))
        assertEquals("1 трек", Format.trackCount(1))
    }

    @Test
    fun shortCounts() {
        assertEquals("950", Format.count(950))
        assertEquals("1,2 тыс.", Format.count(1_200))
        assertEquals("3 тыс.", Format.count(3_000))
        assertEquals("2,5 млн", Format.count(2_500_000))
    }

    @Test
    fun totalDurations() {
        assertEquals("47 мин. 10 сек.", Format.totalDuration(2_830_000))
        assertEquals("4 ч. 45 мин.", Format.totalDuration(17_100_000))
        assertEquals("примерно 4 ч. 45 мин.", Format.totalDuration(17_100_000, approx = true))
        assertEquals("3 мин.", Format.totalDuration(180_000))
    }

    @Test
    fun relativeDates() {
        val now = 1_800_000_000_000L
        val nowSec = now / 1000
        assertEquals("1 минуту назад", Format.relativeDate(nowSec - 10, now))
        assertEquals("5 минут назад", Format.relativeDate(nowSec - 300, now))
        assertEquals("2 часа назад", Format.relativeDate(nowSec - 7_200, now))
        assertEquals("3 дня назад", Format.relativeDate(nowSec - 3 * 86_400, now))
    }
}
