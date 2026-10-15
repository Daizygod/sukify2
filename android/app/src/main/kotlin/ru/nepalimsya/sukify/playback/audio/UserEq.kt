package ru.nepalimsya.sukify.playback.audio

import android.content.Context
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Настройки эквалайзера: 10 полос ±12 дБ и предусиление. */
@Immutable
data class EqSettings(
    val enabled: Boolean = false,
    val preset: String = "flat",
    val preampDb: Float = 0f,
    val gainsDb: List<Float> = List(UserEq.FREQS.size) { 0f },
)

/**
 * Пользовательский эквалайзер (сверх веба). Звуковой поток читает настройки
 * отсюда без блокировок: при каждой смене растёт [version], и процессор деки
 * пересчитывает свои полосы на ближайшем буфере.
 */
object UserEq {
    /** Центры полос — октавы, как в графических эквалайзерах. */
    val FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val LABELS = listOf("31", "62", "125", "250", "500", "1к", "2к", "4к", "8к", "16к")

    /** Полосы чуть шире октавы: соседние сливаются в плавную кривую, без ряби. */
    const val Q = 1.0f
    const val MAX_DB = 12f

    @Volatile var settings: EqSettings = EqSettings()
        private set

    @Volatile var version: Int = 0
        private set

    fun apply(value: EqSettings) {
        settings = value
        version++
    }

    /** Готовые пресеты: название и усиление по полосам (31 Гц … 16 кГц). */
    val PRESETS: LinkedHashMap<String, Pair<String, List<Float>>> = linkedMapOf(
        "flat" to ("Ровно" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        "bass" to ("Бас" to listOf(6f, 5f, 4f, 2f, 0.5f, 0f, 0f, 0f, 0f, 0f)),
        "treble" to ("Высокие" to listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 2f, 4f, 5f, 6f)),
        "vocal" to ("Вокал" to listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
        "electronic" to ("Электроника" to listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f)),
        "rock" to ("Рок" to listOf(4f, 3f, 2f, 0f, -1f, -1f, 0f, 2f, 3f, 4f)),
        "acoustic" to ("Акустика" to listOf(3f, 2f, 1f, 1f, 2f, 2f, 3f, 3f, 2f, 1f)),
        "night" to ("Поздний вечер" to listOf(-1f, -1f, 0f, 0f, 0f, 0f, -1f, -2f, -3f, -4f)),
    )

    /**
     * Суммарная АЧХ (дБ) на частоте [freq] — для кривой на экране: сумма
     * откликов колоколов плюс предусиление, по тем же формулам, что и звук.
     */
    fun responseDb(settings: EqSettings, freq: Float, sampleRate: Int = 48_000): Float {
        var total = settings.preampDb
        val w = 2 * PI * freq / sampleRate
        for ((i, f0) in FREQS.withIndex()) {
            val g = settings.gainsDb.getOrElse(i) { 0f }
            if (g > -0.01f && g < 0.01f) continue
            val a = 10.0.pow(g / 40.0)
            val w0 = 2 * PI * f0 / sampleRate
            val alpha = sin(w0) / (2 * Q)
            val c = cos(w0)
            val b0 = 1 + alpha * a
            val b1 = -2 * c
            val b2 = 1 - alpha * a
            val a0 = 1 + alpha / a
            val a1 = -2 * c
            val a2 = 1 - alpha / a
            total += (magnitudeDb(b0, b1, b2, w) - magnitudeDb(a0, a1, a2, w)).toFloat()
        }
        return total
    }

    private fun magnitudeDb(c0: Double, c1: Double, c2: Double, w: Double): Double {
        val re = c0 + c1 * cos(w) + c2 * cos(2 * w)
        val im = -(c1 * sin(w) + c2 * sin(2 * w))
        return 20 * log10(sqrt(re * re + im * im).coerceAtLeast(1e-9))
    }
}

/**
 * Где эквалайзер хранится: на телефоне, не на сервере — у каждого телефона
 * свои наушники. Загружается при создании плеера.
 */
@Singleton
class EqualizerStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<EqSettings> = _settings.asStateFlow()

    init {
        UserEq.apply(_settings.value)
    }

    fun update(value: EqSettings) {
        _settings.value = value
        UserEq.apply(value)
        prefs.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putString(KEY_PRESET, value.preset)
            .putFloat(KEY_PREAMP, value.preampDb)
            .putString(KEY_GAINS, value.gainsDb.joinToString(","))
            .apply()
    }

    private fun load(): EqSettings {
        val gains = prefs.getString(KEY_GAINS, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == UserEq.FREQS.size }
            ?: List(UserEq.FREQS.size) { 0f }
        return EqSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            preset = prefs.getString(KEY_PRESET, "flat") ?: "flat",
            preampDb = prefs.getFloat(KEY_PREAMP, 0f),
            gainsDb = gains,
        )
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PRESET = "preset"
        const val KEY_PREAMP = "preamp"
        const val KEY_GAINS = "gains"
    }
}
