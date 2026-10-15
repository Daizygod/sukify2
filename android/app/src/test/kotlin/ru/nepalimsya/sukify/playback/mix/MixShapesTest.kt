package ru.nepalimsya.sukify.playback.mix

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Порт форм перехода против веба: эталон снят прямо с frontend/src/lib/mix
 * скриптом android/tools/mix_reference.mjs (src/test/resources/mix_reference.json).
 */
class MixShapesTest {

    private val ref: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("mix_reference.json")!!.readText(),
    ).jsonObject

    private val ts = ref["ts"]!!.jsonArray.map { it.jsonPrimitive.float }

    private fun assertCurve(name: String, points: Points?, expected: JsonElement?) {
        if (expected == null || expected is JsonNull) {
            assertEquals("$name: в вебе кривой нет", null, points)
            return
        }
        val values = expected.jsonArray.map { it.jsonPrimitive.double }
        requireNotNull(points) { "$name: в вебе есть кривая, а у нас нет" }
        ts.forEachIndexed { i, t ->
            // Частоты до 20 кГц во float — допуск относительный.
            assertEquals("$name @ t=$t", values[i], points.valueAt(t).toDouble(), maxOf(1e-3, kotlin.math.abs(values[i]) * 1e-6))
        }
    }

    @Test
    fun `volume shapes match web`() {
        val volume = ref["volume"]!!.jsonObject
        assertEquals(volume.keys, MixShapes.VOLUME.keys)
        for ((id, sides) in volume) {
            val shape = MixShapes.VOLUME.getValue(id)
            assertCurve("$id.out", shape.out, sides.jsonObject["out"])
            assertCurve("$id.in", shape.inn, sides.jsonObject["in"])
        }
    }

    @Test
    fun `eq shapes match web`() {
        val eq = ref["eq"]!!.jsonObject
        assertEquals(eq.keys, MixShapes.EQ.keys)
        for ((id, sides) in eq) {
            val shape = MixShapes.EQ.getValue(id)
            for ((side, ours) in listOf("out" to shape.out, "in" to shape.inn)) {
                val bands = sides.jsonObject[side]!!.jsonObject
                assertCurve("$id.$side.low", ours.low, bands["low"])
                assertCurve("$id.$side.mid", ours.mid, bands["mid"])
                assertCurve("$id.$side.high", ours.high, bands["high"])
            }
        }
    }

    @Test
    fun `filter shapes match web`() {
        val filter = ref["filter"]!!.jsonObject
        assertEquals(filter.keys, MixShapes.FILTER.keys)
        for ((id, spec) in filter) {
            val shape = MixShapes.FILTER.getValue(id)
            for ((side, ours) in listOf("out" to shape.out, "in" to shape.inn)) {
                val web = spec.jsonObject[side]
                if (web == null || web is JsonNull) {
                    assertEquals("$id.$side", null, ours)
                    continue
                }
                val type = web.jsonObject["type"]!!.jsonPrimitive.content
                assertEquals("$id.$side.type", if (type == "lowpass") FilterType.Lowpass else FilterType.Highpass, ours?.type)
                assertCurve("$id.$side", ours?.points, web.jsonObject["values"])
            }
            assertCurve("$id.noise", shape.noise, spec.jsonObject["noise"])
        }
    }

    @Test
    fun `presets resolve like web`() {
        val presets = ref["presets"]!!.jsonObject
        for (id in MixShapes.PRESET_ORDER) {
            val web = presets[id]!!.jsonObject
            for ((matched, key) in listOf(true to "matched", false to "unmatched")) {
                val expected = web[key]!!.jsonObject
                val ours = MixShapes.resolve(id, null, null, null, matched)
                assertEquals("$id/$key", expected.toShapes(), ours)
            }
        }
        val custom = presets["custom"]!!.jsonObject
        assertEquals(custom.toShapes(), MixShapes.resolve("custom", "overlap", "three_band", "hp_out", false))
    }

    @Test
    fun `tempo matching follows web`() {
        for (row in ref["beats"]!!.jsonArray) {
            val o = row.jsonObject
            val a = o["a"]!!.jsonPrimitive.double
            val b = o["b"]!!.jsonPrimitive.double
            assertEquals("$a vs $b", o["match"]!!.jsonPrimitive.boolean, MixShapes.beatsMatch(a, b))
        }
    }

    @Test
    fun `camelot colors, compatibility and titles follow web`() {
        val camelot = ref["camelot"]!!.jsonObject
        for ((key, color) in camelot["colors"]!!.jsonObject) {
            assertEquals(key, color.jsonPrimitive.content, Camelot.hex(key))
        }
        for (row in camelot["pairs"]!!.jsonArray) {
            val o = row.jsonObject
            val a = o["a"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val b = o["b"]!!.jsonPrimitive.content
            assertEquals("$a ~ $b", o["compatible"]!!.jsonPrimitive.boolean, Camelot.compatible(a, b))
        }
        val titles = (camelot["titles"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(
            titles,
            listOf(Camelot.title("9B", "E", "major"), Camelot.title("8A", "A", "minor"), Camelot.title(null), Camelot.title("11B", null)),
        )
    }

    private fun JsonObject.toShapes() = Shapes(
        this["volume"]!!.jsonPrimitive.content,
        this["eq"]!!.jsonPrimitive.content,
        this["filter"]!!.jsonPrimitive.content,
    )
}
