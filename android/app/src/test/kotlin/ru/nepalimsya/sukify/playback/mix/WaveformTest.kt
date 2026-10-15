package ru.nepalimsya.sukify.playback.mix

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.nepalimsya.sukify.data.model.BandDto
import ru.nepalimsya.sukify.data.model.BandsDto
import ru.nepalimsya.sukify.data.model.WaveformDto

/**
 * Волна против веба: высоты столбцов, доли и такты — из waveform.js
 * (эталон: android/tools/mix_reference.mjs → src/test/resources/mix_reference.json).
 */
class WaveformTest {
    private val ref: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("mix_reference.json")!!.readText(),
    ).jsonObject.getValue("waveform").jsonObject

    /** В юнит-тестах нет android.util.Base64 — тот же разбор на java.util. */
    private val decoder: (String?) -> IntArray = { b64 ->
        if (b64.isNullOrEmpty()) IntArray(0) else Base64.getDecoder().decode(b64).let { b -> IntArray(b.size) { b[it].toInt() and 0xFF } }
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun assertColumns(label: String, expected: JsonArray, actual: FloatArray) {
        assertEquals("$label: столбцов", expected.size, actual.size)
        expected.forEachIndexed { i, e ->
            if (e is JsonNull) {
                assertTrue("$label[$i]: вне трека — пусто, а не ${actual[i]}", actual[i].isNaN())
            } else {
                // Эталон снят с пути SVG с точностью 0,1 из 10000.
                assertEquals("$label[$i]", e.jsonPrimitive.double, actual[i].toDouble(), 2e-4)
            }
        }
    }

    @Test
    fun `шесть слоёв полос совпадают с bandPaths`() {
        val bands = ref.getValue("bands").jsonObject
        fun band(name: String) = bands.getValue(name).jsonObject.let { BandDto(rms = it.str("rms"), peak = it.str("peak")) }
        val wave = Waveform.of(WaveformDto(bands = BandsDto(band("lows"), band("mids"), band("highs"))), decoder)!!
        assertTrue(wave.layered)
        for (w in ref.getValue("windows").jsonArray.map { it.jsonObject }) {
            val columns = w.getValue("columns").jsonPrimitive.int
            val out = Array(6) { FloatArray(columns) }
            wave.columns(w.getValue("durationMs").jsonPrimitive.long, w.getValue("fromMs").jsonPrimitive.double, w.getValue("toMs").jsonPrimitive.double, columns, out)
            // Слои в эталоне — в порядке LAYERS веба: снаружи внутрь, как и у нас.
            w.getValue("layers").jsonArray.forEachIndexed { layer, l ->
                val o = l.jsonObject
                assertColumns("${o.str("key")} ${w.getValue("fromMs")}…${w.getValue("toMs")}", o.getValue("values").jsonArray, out[layer])
            }
        }
    }

    @Test
    fun `старый формат — вся полоса и бас, как wavePath`() {
        val legacy = ref.getValue("legacy").jsonObject
        val wave = Waveform.of(WaveformDto(full = legacy.str("full"), bass = legacy.str("bass")), decoder)!!
        assertFalse(wave.layered)
        for (w in legacy.getValue("windows").jsonArray.map { it.jsonObject }) {
            val columns = w.getValue("columns").jsonPrimitive.int
            val out = Array(2) { FloatArray(columns) }
            wave.columns(w.getValue("durationMs").jsonPrimitive.long, w.getValue("fromMs").jsonPrimitive.double, w.getValue("toMs").jsonPrimitive.double, columns, out)
            assertColumns("full ${w.getValue("fromMs")}", w.getValue("full").jsonArray, out[0])
            assertColumns("bass ${w.getValue("fromMs")}", w.getValue("bass").jsonArray, out[1])
        }
    }

    @Test
    fun `без волны — null, битые полосы — старый формат`() {
        assertNull(Waveform.of(null, decoder))
        assertNull(Waveform.of(WaveformDto(), decoder))
        // Полосы разной длины не рисуем — откатываемся к full/bass.
        val broken = WaveformDto(
            bands = BandsDto(BandDto("AAAA", "AAAA"), BandDto("AA==", "AA=="), BandDto("AAAA", "AAAA")),
            full = "AAEC",
        )
        assertFalse(Waveform.of(broken, decoder)!!.layered)
    }

    @Test
    fun `длина такта как barMs`() {
        for (c in ref.getValue("barMs").jsonArray.map { it.jsonObject }) {
            val bpm = c.getValue("bpm").jsonPrimitive.double
            assertEquals("bpm $bpm", c.getValue("ms").jsonPrimitive.double, Waveform.barMs(bpm.takeIf { it > 0 }), 1e-9)
        }
        assertEquals(0.0, Waveform.barMs(null), 0.0)
    }

    @Test
    fun `прилипание к ближайшей доле как snapToBeat`() {
        val snap = ref.getValue("snap").jsonObject
        val beats = snap.getValue("beats").jsonArray.map { it.jsonPrimitive.long }
        for (c in snap.getValue("cases").jsonArray.map { it.jsonObject }) {
            val ms = c.getValue("ms").jsonPrimitive.double
            // У нас время целое: 1203.5 мс из веба — это 1203 или 1204, обе ближе к 969.
            assertEquals("ms $ms", c.getValue("snapped").jsonPrimitive.long, Waveform.snapToBeat(beats, ms.toLong()))
        }
        assertEquals(777L, Waveform.snapToBeat(emptyList(), 777))
        assertEquals(777L, Waveform.snapToBeat(null, 777))
    }
}
