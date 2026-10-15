package ru.nepalimsya.sukify.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Test

class SvgPathTest {

    @Test
    fun `compact arc flags are split from coordinates`() {
        // Начало пути heartFill из Icon.vue.
        assertEquals(
            "M 15.724 4.22 A 4.313 4.313 0 0 0 12.192 .814 a 4.269 4.269 0 0 0 -3.622 1.13",
            normalizePath("M15.724 4.22A4.313 4.313 0 0012.192.814a4.269 4.269 0 00-3.622 1.13"),
        )
    }

    @Test
    fun `flags followed by separators stay intact`() {
        assertEquals("a 8 8 0 1 0 0 16", normalizePath("a8 8 0 1 0 0 16"))
        assertEquals("A 8 8 0 0 1 8 0", normalizePath("A8,8,0,0,1,8,0"))
    }

    @Test
    fun `repeated arcs keep counting arguments`() {
        assertEquals(
            "a 6.5 6.5 0 1 1 13 0 6.5 6.5 0 0 1 -13 0",
            normalizePath("a6.5 6.5 0 1 1 13 0 6.5 6.5 0 0 1-13 0"),
        )
    }

    @Test
    fun `numbers without separators are split at sign and second dot`() {
        assertEquals("M 1.5 -2.5 .25 L 3e-2 4", normalizePath("M1.5-2.5.25L3e-2 4"))
    }
}
