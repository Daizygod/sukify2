package ru.nepalimsya.sukify.playback.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import ru.nepalimsya.sukify.playback.mix.FilterType
import ru.nepalimsya.sukify.playback.mix.MixShapes
import ru.nepalimsya.sukify.playback.mix.Points
import ru.nepalimsya.sukify.playback.mix.valueAt

/**
 * Что дека делает со звуком во время перехода — всё по медиавремени деки:
 * громкость, три полосы эквалайзера, НЧ/ВЧ-фильтр и подмешанный шум.
 * Отсутствующий параметр = нейтральный (дека звучит как есть).
 */
class DeckAutomation(
    val gain: Envelope = Envelope.Unity,
    val low: Envelope? = null,
    val mid: Envelope? = null,
    val high: Envelope? = null,
    val filterType: FilterType? = null,
    val filter: Envelope? = null,
    val noise: Envelope? = null,
) {
    /** Та же автоматизация, сдвинутая во времени (переход стартовал позже плана). */
    fun shiftedBy(deltaUs: Long): DeckAutomation = DeckAutomation(
        gain = gain.shiftedBy(deltaUs),
        low = low?.shiftedBy(deltaUs),
        mid = mid?.shiftedBy(deltaUs),
        high = high?.shiftedBy(deltaUs),
        filterType = filterType,
        filter = filter?.shiftedBy(deltaUs),
        noise = noise?.shiftedBy(deltaUs),
    )

    /** Есть ли что-то кроме громкости — иначе обработка идёт быстрым путём. */
    val hasFx: Boolean get() = low != null || mid != null || high != null || filter != null || noise != null

    companion object {
        val Neutral = DeckAutomation()

        /** Из кривой [points] (t 0..1) — таблица на отрезке медиавремени. */
        fun envelope(points: Points?, startUs: Long, durationUs: Long): Envelope? =
            points?.let { p -> Envelope.sampled(startUs, durationUs, STEPS) { t -> p.valueAt(t) } }

        private const val STEPS = 256
    }
}

/**
 * Биквад-фильтр (Transposed Direct Form II) с коэффициентами ровно как у
 * BiquadFilterNode Web Audio — веб и приложение звучат одинаково.
 */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    // Состояние для каждого канала (до 8).
    private val z1 = FloatArray(8)
    private val z2 = FloatArray(8)

    /** Пропускать без изменений (полка 0 дБ, фильтр открыт) — экономим такты. */
    var bypass = true
        private set

    fun reset() {
        z1.fill(0f)
        z2.fill(0f)
    }

    fun process(x: Float, ch: Int): Float {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / na0).toFloat()
        b1 = (nb1 / na0).toFloat()
        b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat()
        a2 = (na2 / na0).toFloat()
    }

    /** НЧ/ВЧ-срез; Q, как в Web Audio, — в децибелах. */
    fun passFilter(type: FilterType, freq: Float, sampleRate: Int, qDb: Float = 0.7f) {
        val f = freq.coerceIn(10f, sampleRate / 2f - 1f)
        val open = when (type) {
            FilterType.Lowpass -> f >= MixShapes.OPEN_LP - 1f
            FilterType.Highpass -> f <= MixShapes.OPEN_HP + 1f
        }
        bypass = open
        if (open) return
        val w0 = 2 * PI * f / sampleRate
        val c = cos(w0)
        val alpha = sin(w0) / (2 * 10.0.pow(qDb / 20.0))
        when (type) {
            FilterType.Lowpass -> set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            FilterType.Highpass -> set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }
    }

    /** Низкая полка (Web Audio lowshelf, наклон S = 1). */
    fun lowShelf(freq: Float, gainDb: Float, sampleRate: Int) = shelf(freq, gainDb, sampleRate, low = true)

    fun highShelf(freq: Float, gainDb: Float, sampleRate: Int) = shelf(freq, gainDb, sampleRate, low = false)

    private fun shelf(freq: Float, gainDb: Float, sampleRate: Int, low: Boolean) {
        bypass = gainDb > -0.01f && gainDb < 0.01f
        if (bypass) return
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * freq / sampleRate
        val c = cos(w0)
        val alpha = sin(w0) / 2 * sqrt(2.0)
        val k = 2 * sqrt(a) * alpha
        if (low) {
            set(
                a * ((a + 1) - (a - 1) * c + k),
                2 * a * ((a - 1) - (a + 1) * c),
                a * ((a + 1) - (a - 1) * c - k),
                (a + 1) + (a - 1) * c + k,
                -2 * ((a - 1) + (a + 1) * c),
                (a + 1) + (a - 1) * c - k,
            )
        } else {
            set(
                a * ((a + 1) + (a - 1) * c + k),
                -2 * a * ((a - 1) + (a + 1) * c),
                a * ((a + 1) + (a - 1) * c - k),
                (a + 1) - (a - 1) * c + k,
                2 * ((a - 1) - (a + 1) * c),
                (a + 1) - (a - 1) * c - k,
            )
        }
    }

    /** Колокол (Web Audio peaking; Q — линейный). */
    fun peaking(freq: Float, gainDb: Float, q: Float, sampleRate: Int) {
        bypass = gainDb > -0.01f && gainDb < 0.01f
        if (bypass) return
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * freq / sampleRate
        val c = cos(w0)
        val alpha = sin(w0) / (2 * q)
        set(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
    }
}
