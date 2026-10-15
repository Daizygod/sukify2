package ru.nepalimsya.sukify.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.nepalimsya.sukify.playback.mix.FilterType
import ru.nepalimsya.sukify.playback.mix.MixShapes

class DeckProcessorTest {

    private val rate = 44_100

    /** Синус [freq] Гц, моно → стерео float. */
    private fun sine(freq: Double, seconds: Double, amp: Float = 0.5f): FloatArray {
        val frames = (rate * seconds).toInt()
        return FloatArray(frames * 2) { i -> (amp * sin(2 * PI * freq * (i / 2) / rate)).toFloat() }
    }

    private fun process(processor: DeckProcessor, input: FloatArray): FloatArray {
        processor.configure(AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        processor.flush()
        val out = ArrayList<Float>(input.size)
        // Кормим кусками по 4096 кадров, как ExoPlayer.
        var pos = 0
        while (pos < input.size) {
            val n = minOf(8192, input.size - pos)
            val buffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until n) buffer.putFloat(input[pos + i])
            buffer.flip()
            processor.queueInput(buffer)
            val result = processor.output.order(ByteOrder.nativeOrder())
            while (result.hasRemaining()) out += result.float
            pos += n
        }
        return out.toFloatArray()
    }

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return sqrt(sum / (to - from))
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    @Test
    fun `neutral automation passes signal through`() {
        val input = sine(440.0, 0.2)
        val output = process(DeckProcessor(), input)
        assertEquals(rms(input), rms(output), 1e-4)
    }

    @Test
    fun `gain envelope follows media time`() {
        val p = DeckProcessor()
        // Затухание 1 → 0 на отрезке 0,5…1,0 с медиавремени.
        p.envelope = Envelope.sampled(500_000, 500_000) { t -> 1 - t }
        val output = process(p, sine(440.0, 1.5))
        val before = rms(output, 0, rate) // первые полсекунды (стерео → 2 сэмпла на кадр)
        val after = rms(output, (rate * 2 * 1.1).toInt(), output.size)
        assertTrue("до перехода звук на месте: $before", before > 0.3)
        assertTrue("после перехода тишина: $after", after < 1e-3)
    }

    @Test
    fun `lowpass sweep removes highs and keeps lows`() {
        val auto = DeckAutomation(
            filterType = FilterType.Lowpass,
            filter = Envelope(0, 0, floatArrayOf(250f)),
        )
        val high = DeckProcessor().apply { automation = auto }
        val low = DeckProcessor().apply { automation = auto }
        val highIn = sine(5000.0, 0.3)
        val lowIn = sine(80.0, 0.3)
        val highOut = process(high, highIn)
        val lowOut = process(low, lowIn)
        // Без переходного процесса в начале.
        val skip = rate / 5
        assertTrue("5 кГц через НЧ 250 Гц почти исчезает", db(rms(highOut, skip) / rms(highIn, skip)) < -40)
        assertTrue("80 Гц проходит", abs(db(rms(lowOut, skip) / rms(lowIn, skip))) < 1.5)
    }

    @Test
    fun `low shelf cut kills bass but not mids`() {
        val auto = DeckAutomation(low = Envelope(0, 0, floatArrayOf(-40f)))
        val bass = process(DeckProcessor().apply { automation = auto }, sine(40.0, 0.5))
        val mids = process(DeckProcessor().apply { automation = auto }, sine(2000.0, 0.5))
        val skip = rate / 5
        assertTrue("бас под −40 дБ полкой", db(rms(bass, skip) / rms(sine(40.0, 0.5), skip)) < -30)
        assertTrue("середина почти не тронута", abs(db(rms(mids, skip) / rms(sine(2000.0, 0.5), skip))) < 1.5)
    }

    @Test
    fun `open filter is bypassed`() {
        val b = Biquad()
        b.passFilter(FilterType.Lowpass, MixShapes.OPEN_LP, rate)
        assertTrue(b.bypass)
        b.passFilter(FilterType.Highpass, MixShapes.OPEN_HP, rate)
        assertTrue(b.bypass)
        b.lowShelf(200f, 0f, rate)
        assertTrue(b.bypass)
    }

    @Test
    fun `noise follows its shape inside the window`() {
        // Шум формы «Шум + затухание в конце»: 0 до 70 % перехода, пик к 90 %.
        val noise = MixShapes.filter("noise_fade_end").noise
        val auto = DeckAutomation(noise = DeckAutomation.envelope(noise, 0, 1_000_000))
        val silence = FloatArray(rate * 2) // 1 с тишины
        val output = process(DeckProcessor().apply { automation = auto }, silence)
        val frame = { s: Double -> (rate * 2 * s).toInt() }
        assertEquals(0.0, rms(output, 0, frame(0.6)), 1e-6)
        assertTrue("шум к концу перехода", rms(output, frame(0.85), frame(0.95)) > 0.02)
    }

    @Test
    fun `shifted automation starts earlier`() {
        val env = Envelope.sampled(1_000_000, 1_000_000) { t -> t }
        val shifted = DeckAutomation(gain = env).shiftedBy(-500_000)
        assertEquals(0.5f, shifted.gain.valueAt(1_000_000), 0.01f)
    }

    @Test
    fun `эквалайзер поднимает свою полосу ровно как на кривой`() {
        val gains = MutableList(UserEq.FREQS.size) { 0f }.also { it[5] = 6f } // 1 кГц
        val settings = EqSettings(enabled = true, preset = "custom", gainsDb = gains)
        UserEq.apply(settings)
        try {
            fun gainAt(freq: Double): Double {
                val input = sine(freq, 0.5, amp = 0.2f)
                val output = process(DeckProcessor(), input)
                val from = output.size / 4 // без переходного процесса
                return db(rms(output, from) / rms(input, from))
            }
            val at1k = gainAt(1000.0)
            assertEquals(6.0, at1k, 0.3)
            // Кривая на экране считается теми же формулами, что и звук.
            assertEquals(UserEq.responseDb(settings, 1000f, rate).toDouble(), at1k, 0.2)
            assertEquals(UserEq.responseDb(settings, 125f, rate).toDouble(), gainAt(125.0), 0.2)
            assertTrue("далёкая полоса почти не трогается", abs(gainAt(125.0)) < 0.5)
        } finally {
            UserEq.apply(EqSettings())
        }
    }

    @Test
    fun `выключенный эквалайзер не трогает звук`() {
        UserEq.apply(EqSettings(enabled = false, gainsDb = List(UserEq.FREQS.size) { 12f }))
        try {
            val input = sine(1000.0, 0.2)
            assertEquals(rms(input), rms(process(DeckProcessor(), input)), 1e-4)
        } finally {
            UserEq.apply(EqSettings())
        }
    }
}
