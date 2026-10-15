package ru.nepalimsya.sukify.playback.audio

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Значения сверены с buildCurve из frontend/src/lib/curves.js. */
class CurvesTest {

    @Test
    fun equalPowerKeepsLoudnessConstant() {
        for (i in 0..20) {
            val t = i / 20f
            val out = CurveType.EqualPower.value(1f, 0f, t)
            val inc = CurveType.EqualPower.value(0f, 1f, t)
            assertEquals("t=$t", 1f, sqrt(out * out + inc * inc), 1e-4f)
        }
    }

    @Test
    fun shapesMatchWebAtQuarter() {
        // JS: buildCurve(0, 1, type, 5)[1] — t = 0.25.
        assertEquals(0.25f, CurveType.Linear.value(0f, 1f, 0.25f), 1e-6f)
        assertEquals(0.0625f, CurveType.Exponential.value(0f, 1f, 0.25f), 1e-6f)
        assertEquals(0.5f, CurveType.Logarithmic.value(0f, 1f, 0.25f), 1e-6f)
        assertEquals(0.15625f, CurveType.SCurve.value(0f, 1f, 0.25f), 1e-6f)
        assertEquals(0.38268343f, CurveType.EqualPower.value(0f, 1f, 0.25f), 1e-5f)
        // Убывающая равной мощности: 1 − (1 − cos(π/8)) = cos(π/8).
        assertEquals(0.9238795f, CurveType.EqualPower.value(1f, 0f, 0.25f), 1e-5f)
    }

    @Test
    fun envelopeInterpolatesAndClamps() {
        val env = Envelope.sampled(startUs = 1_000_000, durationUs = 2_000_000) { 1f - it }
        assertEquals(1f, env.valueAt(0), 0f)
        assertEquals(1f, env.valueAt(1_000_000), 0f)
        assertEquals(0.5f, env.valueAt(2_000_000), 0.01f)
        assertEquals(0f, env.valueAt(3_000_000), 0f)
        assertEquals(0f, env.valueAt(9_000_000), 0f)
    }

    @Test
    fun limiterIsTransparentBelowKneeAndNeverClips() {
        assertEquals(0.5f, DeckProcessor.limit(0.5f), 0f)
        assertEquals(-0.8f, DeckProcessor.limit(-0.8f), 0f)
        for (x in listOf(0.95f, 1.5f, 4f, 40f)) {
            val y = DeckProcessor.limit(x)
            assertTrue("$x → $y", y < 1f && y > 0.89f)
            assertEquals(-y, DeckProcessor.limit(-x), 0f)
        }
        // Монотонный: громче на входе — не тише на выходе.
        var prev = 0f
        for (i in 1..400) {
            val y = DeckProcessor.limit(i / 100f)
            assertTrue(y >= prev)
            prev = y
        }
        assertTrue(abs(DeckProcessor.limit(0.891f) - 0.891f) < 1e-6f)
    }
}
