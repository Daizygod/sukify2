package ru.nepalimsya.sukify.playback.mix

import android.util.Base64
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import ru.nepalimsya.sukify.data.model.WaveformDto

/**
 * Волна трека — порт frontend/src/lib/mix/waveform.js.
 *
 * С бэка приходит base64 байтов 0..255, по значению на корзину. Новый
 * формат — три полосы (низ, середина, верх), у каждой тело (RMS) и гребень
 * (максимум); рисуются стопкой от середины. Старый — вся полоса и бас.
 *
 * Разбор base64 и масштаб — один раз на трек; раскладка по столбцам окна
 * пишет в готовые массивы, без выделений, — перетаскивание идёт на 120 Гц.
 */
class Waveform private constructor(
    /** Сколько корзин на весь трек. */
    val count: Int,
    private val bands: Array<IntArray>?,
    private val legacy: Array<IntArray>?,
    private val scale: Float,
) {
    /** Черновик высот одного столбца (рисуем только из главного потока). */
    private val values = FloatArray(6)

    /** Новый ли формат (шесть слоёв) или старый (два). */
    val layered: Boolean get() = bands != null

    val layerCount: Int get() = if (layered) 6 else 2

    /**
     * Высоты слоёв по столбцам окна [fromMs, toMs] трека длиной [durationMs]:
     * out[слой][столбец] — доля половины высоты панели (0..1), NaN — столбец
     * вне трека. Слои — снаружи внутрь (см. [LAYER_COLORS]).
     */
    fun columns(durationMs: Long, fromMs: Double, toMs: Double, columns: Int, out: Array<FloatArray>) {
        val span = toMs - fromMs
        for (i in 0 until columns) {
            val t0 = fromMs + span * i / columns
            val t1 = fromMs + span * (i + 1) / columns
            if (t1 <= 0 || t0 >= durationMs || durationMs <= 0) {
                for (layer in out) layer[i] = Float.NaN
                continue
            }
            val b0 = max(0, floor(max(0.0, t0) / durationMs * count).toInt())
            val b1 = min(count, max(b0 + 1, ceil(min(durationMs.toDouble(), t1) / durationMs * count).toInt()))
            if (bands != null) {
                // Каждая полоса: гребень — максимум по корзинам столбца, тело не выше гребня.
                var hTop = 0f
                var mTop = 0f
                for (band in 2 downTo 0) { // верх, середина, низ — изнутри наружу
                    val peak = peakIn(bands[band * 2 + 1], b0, b1).toFloat()
                    val rms = min(peak, peakIn(bands[band * 2], b0, b1).toFloat())
                    val top = rms + crest(rms, peak)
                    when (band) {
                        2 -> {
                            values[5] = rms // highs-rms
                            values[4] = top // highs-peak
                            hTop = top
                        }
                        1 -> {
                            values[3] = hTop + rms // mids-rms
                            values[2] = hTop + top // mids-peak
                            mTop = top
                        }
                        0 -> {
                            values[1] = hTop + mTop + rms // lows-rms
                            values[0] = hTop + mTop + top // lows-peak
                        }
                    }
                }
                // Десятая часть высоты свободна: волна у края читается как обрезанная.
                val s = scale / 0.9f
                for (layer in 0 until 6) out[layer][i] = min(1f, values[layer] / s)
            } else if (legacy != null) {
                out[0][i] = peakIn(legacy[0], b0, b1) / 255f
                out[1][i] = peakIn(legacy[1], b0, b1) / 255f
            }
        }
    }

    companion object {
        /** Цвета слоёв снаружи внутрь (сняты с оригинала): низ синий, середина рыжая, верх белый. */
        val LAYER_COLORS = longArrayOf(0xFF1737B1, 0xFF1737B1, 0xFFC98335, 0xFF844B1F, 0xFFEDFFFF, 0xFFF3E6D0)
        val LEGACY_COLORS = longArrayOf(0xFF1737B1, 0xFFC98335)

        /** Насколько гребень может подняться над телом — иначе зажатый мастер рисуется кирпичом. */
        private const val CREST_LIMIT = 0.55f

        private fun crest(rms: Float, peak: Float): Float = min(max(0f, peak - rms), rms * CREST_LIMIT)

        private fun peakIn(values: IntArray, b0: Int, b1: Int): Int {
            var top = 0
            for (b in b0 until b1) if (values[b] > top) top = values[b]
            return top
        }

        private fun decode(b64: String?): IntArray {
            if (b64.isNullOrEmpty()) return IntArray(0)
            return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                ?.let { bytes -> IntArray(bytes.size) { bytes[it].toInt() and 0xFF } }
                ?: IntArray(0)
        }

        /** Разобрать волну из ответа анализа; null — волны ещё нет. */
        fun of(dto: WaveformDto?, decoder: (String?) -> IntArray = ::decode): Waveform? {
            if (dto == null) return null
            val b = dto.bands
            if (b?.lows?.peak != null) {
                // Порядок: lows.rms, lows.peak, mids.rms, mids.peak, highs.rms, highs.peak.
                val arrays = arrayOf(
                    decoder(b.lows.rms), decoder(b.lows.peak),
                    decoder(b.mids?.rms), decoder(b.mids?.peak),
                    decoder(b.highs?.rms), decoder(b.highs?.peak),
                )
                val count = arrays[1].size
                if (count > 0 && arrays.all { it.size == count }) {
                    // Масштаб общий на весь трек: самая громкая корзина занимает панель целиком.
                    var scale = 1f
                    for (i in 0 until count) {
                        var total = 0f
                        for (band in 0 until 3) {
                            val rms = arrays[band * 2][i].toFloat()
                            total += rms + crest(rms, arrays[band * 2 + 1][i].toFloat())
                        }
                        if (total > scale) scale = total
                    }
                    return Waveform(count, arrays, null, scale)
                }
            }
            val full = decoder(dto.full)
            if (full.isEmpty()) return null
            val bass = decoder(dto.bass).takeIf { it.size == full.size } ?: IntArray(full.size)
            return Waveform(full.size, null, arrayOf(full, bass), 255f)
        }

        /** Длина такта в миллисекундах при данном темпе (такт — четыре доли). */
        fun barMs(bpm: Double?, beatsPerBar: Int = 4): Double = if (bpm == null || bpm <= 0) 0.0 else 60_000.0 / bpm * beatsPerBar

        /** Ближайшая доля к моменту [ms] — по ней «прилипают» границы перекрытия. */
        fun snapToBeat(beats: List<Long>?, ms: Long): Long {
            if (beats.isNullOrEmpty()) return ms
            var best = beats[0]
            var bestDiff = abs(beats[0] - ms)
            for (b in beats) {
                val d = abs(b - ms)
                if (d < bestDiff) {
                    bestDiff = d
                    best = b
                }
            }
            return best
        }
    }
}
