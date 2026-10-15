package ru.nepalimsya.sukify.playback.mix

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Камелот-круг — порт frontend/src/lib/mix/camelot.js: тональность как
 * «номер + сторона» (B — мажор, A — минор). Соседние номера и одноимённые
 * пары звучат вместе — по этим меткам и подбирают треки для микса.
 */
object Camelot {
    private val B_COLORS = mapOf(
        1 to 0x05ECCB, 2 to 0x3DED82, 3 to 0x8DF25E, 4 to 0xDECA73,
        5 to 0xFEA57C, 6 to 0xFF8893, 7 to 0xFF80B4, 8 to 0xEB88DC,
        9 to 0xCB90FF, 10 to 0xA0B6FF, 11 to 0x51B8FB, 12 to 0x0DE8EC,
    )

    private val PATTERN = Regex("^(\\d{1,2})([AB])$", RegexOption.IGNORE_CASE)

    /** Текст на плашке всегда тёмный — фон светлый во всех 24 вариантах. */
    const val TEXT = 0xFF121212

    private val NOTE_RU = mapOf(
        "C" to "до", "C#" to "до-диез", "D" to "ре", "D#" to "ми-бемоль", "E" to "ми", "F" to "фа",
        "F#" to "фа-диез", "G" to "соль", "G#" to "ля-бемоль", "A" to "ля", "A#" to "си-бемоль", "B" to "си",
    )

    /** Цвет плашки тональности: «9B» → #CB90FF; сторона A — тот же цвет, разбавленный белым на 30 %. */
    fun color(camelot: String?): Int? {
        val m = PATTERN.find(camelot?.trim().orEmpty()) ?: return null
        val base = B_COLORS[m.groupValues[1].toInt()] ?: return null
        return if (m.groupValues[2].uppercase() == "B") base else lighten(base)
    }

    /** «#RRGGBB» — как в вебе (для тестов и отладки). */
    fun hex(camelot: String?): String? = color(camelot)?.let { "#%06X".format(it) }

    private fun lighten(rgb: Int, amount: Double = 0.3): Int {
        fun mix(c: Int) = (c * (1 - amount) + 255 * amount).roundToInt()
        val r = mix((rgb shr 16) and 255)
        val g = mix((rgb shr 8) and 255)
        val b = mix(rgb and 255)
        return (r shl 16) or (g shl 8) or b
    }

    /** Подсказка к плашке: «9B · ми мажор». */
    fun title(camelot: String?, key: String? = null, scale: String? = null): String {
        if (camelot.isNullOrEmpty()) return "Тональность не определена"
        if (key.isNullOrEmpty()) return camelot
        val note = NOTE_RU[key] ?: key
        return "$camelot · $note ${if (scale == "minor") "минор" else "мажор"}"
    }

    /** Дружат ли тональности: тот же номер, соседний номер той же стороны. */
    fun compatible(a: String?, b: String?): Boolean {
        val pa = PATTERN.find(a?.trim().orEmpty()) ?: return false
        val pb = PATTERN.find(b?.trim().orEmpty()) ?: return false
        val na = pa.groupValues[1].toInt()
        val nb = pb.groupValues[1].toInt()
        val sa = pa.groupValues[2].uppercase()
        val sb = pb.groupValues[2].uppercase()
        if (na == nb) return true
        val step = min(abs(na - nb), 12 - abs(na - nb))
        return step == 1 && sa == sb
    }
}
