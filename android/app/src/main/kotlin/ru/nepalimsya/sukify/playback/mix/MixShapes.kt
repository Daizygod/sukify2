package ru.nepalimsya.sukify.playback.mix

import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Формы кривых перехода — порт frontend/src/lib/mix/shapes.js один в один
 * (тесты сверяют значения с вебом). Одна таблица кормит и редактор (рисует
 * кривые поверх волны), и плеер (ведёт по ним громкость, эквалайзер и фильтр).
 * Время нормализовано: t = 0 — начало перекрытия, t = 1 — конец.
 */

/** Как кривая подходит к точке. */
enum class Ease {
    Lin, Smooth, In, Out, Cos, Sin, Step;

    fun apply(t: Float): Float = when (this) {
        Lin -> t
        Smooth -> t * t * (3 - 2 * t)
        In -> t * t
        Out -> sqrt(t)
        Cos -> 1 - cos(t * PI.toFloat() / 2)
        Sin -> sin(t * PI.toFloat() / 2)
        Step -> 1f
    }
}

/** Точка кривой: [t, значение, тип подхода]. */
@Immutable
data class P(val t: Float, val v: Float, val ease: Ease = Ease.Lin)

typealias Points = List<P>

/** Значение кусочно-заданной кривой в момент t (0..1) — valueAt() в вебе. */
fun Points.valueAt(t: Float): Float {
    if (isEmpty()) return 0f
    if (t <= this[0].t) return this[0].v
    for (i in 1 until size) {
        val (t1, v1, ease) = this[i]
        if (t > t1) continue
        val (t0, v0) = this[i - 1]
        if (t1 == t0) return v1
        if (ease == Ease.Step) return if (t < t1) v0 else v1
        val k = ease.apply((t - t0) / (t1 - t0))
        return v0 + (v1 - v0) * k
    }
    return last().v
}

/** Громкость: пара кривых 0..1 для уходящего и приходящего. */
@Immutable
data class VolumeShape(val title: String, val out: Points, val inn: Points)

/** Эквалайзер: полосы в дБ; полосы, которых нет, не трогаются. */
@Immutable
data class EqSide(val low: Points? = null, val mid: Points? = null, val high: Points? = null) {
    val isEmpty: Boolean get() = low == null && mid == null && high == null
}

@Immutable
data class EqShape(val title: String, val out: EqSide, val inn: EqSide)

enum class FilterType { Lowpass, Highpass }

/** Фильтр стороны: тип и частота среза в герцах. */
@Immutable
data class FilterSide(val type: FilterType, val points: Points)

@Immutable
data class FilterShape(val title: String, val out: FilterSide?, val inn: FilterSide?, val noise: Points? = null)

/** Готовая тройка форм перехода. */
@Immutable
data class Shapes(val volume: String, val eq: String, val filter: String)

@Immutable
data class Preset(
    val title: String,
    val volume: String? = null,
    val eq: String? = null,
    val filter: String? = null,
    val auto: Boolean = false,
    val custom: Boolean = false,
)

object MixShapes {

    const val OPEN_LP = 20_000f
    const val OPEN_HP = 20f
    private const val CUT = -40f

    private fun pts(vararg p: P): Points = p.toList()

    val VOLUME: Map<String, VolumeShape> = linkedMapOf(
        "crossfade" to VolumeShape(
            "Плавный переход между треками",
            out = pts(P(0f, 1f), P(1f, 0f, Ease.Cos)),
            inn = pts(P(0f, 0f), P(1f, 1f, Ease.Sin)),
        ),
        "slow_crossfade" to VolumeShape(
            "Медленный плавный переход между треками",
            out = pts(P(0f, 1f), P(0.25f, 0.95f, Ease.Smooth), P(1f, 0f, Ease.Smooth)),
            inn = pts(P(0f, 0f), P(0.75f, 0.95f, Ease.Smooth), P(1f, 1f, Ease.Smooth)),
        ),
        "overlap" to VolumeShape(
            "Перекрытие",
            out = pts(P(0f, 1f), P(0.97f, 1f), P(1f, 0f, Ease.Lin)),
            inn = pts(P(0f, 1f), P(1f, 1f)),
        ),
        "rise_fade" to VolumeShape(
            "Усиление + затухание звука",
            out = pts(P(0f, 1f), P(0.4f, 0.9f, Ease.Smooth), P(1f, 0f, Ease.In)),
            inn = pts(P(0f, 0f), P(1f, 1f, Ease.In)),
        ),
        "cut_in_fade_out" to VolumeShape(
            "Резкий вход + затухание звука",
            out = pts(P(0f, 1f), P(1f, 0f, Ease.Smooth)),
            inn = pts(P(0f, 1f, Ease.Step), P(1f, 1f)),
        ),
        "rise_cut_out" to VolumeShape(
            "Усиление звука + резкий выход",
            out = pts(P(0f, 1f), P(0.97f, 1f), P(1f, 0f, Ease.Step)),
            inn = pts(P(0f, 0f), P(1f, 1f, Ease.Smooth)),
        ),
        "hard_swap" to VolumeShape(
            "Резкий переход в середине",
            out = pts(P(0f, 1f), P(0.5f, 1f), P(0.5f, 0f, Ease.Step), P(1f, 0f)),
            inn = pts(P(0f, 0f), P(0.5f, 0f), P(0.5f, 1f, Ease.Step), P(1f, 1f)),
        ),
        "rise_fast_out" to VolumeShape(
            "Нарастание + быстрый выход",
            out = pts(P(0f, 1f), P(0.35f, 0f, Ease.In)),
            inn = pts(P(0f, 0f), P(1f, 1f, Ease.Smooth)),
        ),
    )

    val EQ: Map<String, EqShape> = linkedMapOf(
        "none" to EqShape("Нет", EqSide(), EqSide()),
        "bass_swap_center" to EqShape(
            "Замена баса в центре",
            out = EqSide(low = pts(P(0f, 0f), P(0.5f, 0f), P(0.55f, CUT, Ease.Lin), P(1f, CUT))),
            inn = EqSide(low = pts(P(0f, CUT), P(0.5f, CUT), P(0.55f, 0f, Ease.Lin), P(1f, 0f))),
        ),
        "bass_swap_end" to EqShape(
            "Замена баса в конце",
            out = EqSide(low = pts(P(0f, 0f), P(0.85f, 0f), P(0.9f, CUT, Ease.Lin), P(1f, CUT))),
            inn = EqSide(low = pts(P(0f, CUT), P(0.85f, CUT), P(0.9f, 0f, Ease.Lin), P(1f, 0f))),
        ),
        "bass_swap_start" to EqShape(
            "Замена баса в начале",
            out = EqSide(low = pts(P(0f, 0f), P(0.1f, 0f), P(0.15f, CUT, Ease.Lin), P(1f, CUT))),
            inn = EqSide(low = pts(P(0f, CUT), P(0.1f, CUT), P(0.15f, 0f, Ease.Lin), P(1f, 0f))),
        ),
        "three_band" to EqShape(
            "Трёхполосное затухание",
            out = EqSide(
                low = pts(P(0f, 0f), P(0.6f, CUT, Ease.Smooth)),
                mid = pts(P(0f, 0f), P(0.8f, CUT, Ease.Smooth)),
                high = pts(P(0f, 0f), P(1f, CUT, Ease.Smooth)),
            ),
            inn = EqSide(
                low = pts(P(0f, CUT), P(0.6f, 0f, Ease.Smooth)),
                mid = pts(P(0f, CUT), P(0.4f, 0f, Ease.Smooth)),
                high = pts(P(0f, CUT), P(0.2f, 0f, Ease.Smooth)),
            ),
        ),
        "bass_cut_hard" to EqShape(
            "Резкий срез баса",
            out = EqSide(low = pts(P(0f, 0f), P(0.02f, CUT, Ease.Step), P(1f, CUT))),
            inn = EqSide(low = pts(P(0f, CUT), P(0.98f, CUT), P(1f, 0f, Ease.Step))),
        ),
        "bass_cut_soft" to EqShape(
            "Плавный срез баса",
            out = EqSide(low = pts(P(0f, 0f), P(1f, CUT, Ease.Smooth))),
            inn = EqSide(low = pts(P(0f, CUT), P(1f, 0f, Ease.Smooth))),
        ),
        "start_fade" to EqShape(
            "Начать затухание звука",
            out = EqSide(
                low = pts(P(0f, 0f), P(1f, -24f, Ease.In)),
                mid = pts(P(0f, 0f), P(1f, -18f, Ease.In)),
                high = pts(P(0f, 0f), P(1f, -12f, Ease.In)),
            ),
            inn = EqSide(),
        ),
        "bass_fade" to EqShape(
            "Затухание баса",
            out = EqSide(low = pts(P(0f, 0f), P(1f, CUT, Ease.In))),
            inn = EqSide(low = pts(P(0f, CUT), P(1f, 0f, Ease.Out))),
        ),
    )

    private val LP_OUT = FilterSide(FilterType.Lowpass, pts(P(0f, OPEN_LP), P(1f, 250f, Ease.In)))
    private val LP_IN = FilterSide(FilterType.Lowpass, pts(P(0f, 250f), P(1f, OPEN_LP, Ease.Out)))
    private val HP_OUT = FilterSide(FilterType.Highpass, pts(P(0f, OPEN_HP), P(1f, 6000f, Ease.In)))
    private val HP_IN = FilterSide(FilterType.Highpass, pts(P(0f, 6000f), P(1f, OPEN_HP, Ease.Out)))

    val FILTER: Map<String, FilterShape> = linkedMapOf(
        "none" to FilterShape("Нет", null, null),
        "lp_out" to FilterShape("Выход через НЧ-фильтр", LP_OUT, null),
        "lp_in" to FilterShape("Вход через НЧ-фильтр", null, LP_IN),
        "lp_in_out" to FilterShape("Вход и выход через НЧ-фильтр", LP_OUT, LP_IN),
        "lp_in_hp_out" to FilterShape("Вход через НЧ-фильтр, выход через ВЧ-фильтр", HP_OUT, LP_IN),
        "hp_out" to FilterShape("Выход через ВЧ-фильтр", HP_OUT, null),
        "hp_in" to FilterShape("Вход через ВЧ-фильтр", null, HP_IN),
        "hp_in_out" to FilterShape("Вход и выход через ВЧ-фильтр", HP_OUT, HP_IN),
        "hp_in_lp_out" to FilterShape("Вход через ВЧ-фильтр, выход через НЧ-фильтр", LP_OUT, HP_IN),
        "hp_out_half" to FilterShape(
            "Выход через ВЧ-фильтр половинной мощности",
            FilterSide(FilterType.Highpass, pts(P(0f, OPEN_HP), P(1f, 1800f, Ease.In))),
            null,
        ),
        "noise_fade_end" to FilterShape(
            "Шум + затухание в конце",
            FilterSide(FilterType.Lowpass, pts(P(0f, OPEN_LP), P(0.7f, OPEN_LP), P(1f, 400f, Ease.In))),
            null,
            // Белый шум поверх перехода — его подмешивает уходящая дека.
            noise = pts(P(0f, 0f), P(0.7f, 0f), P(0.9f, 0.18f, Ease.Smooth), P(1f, 0f, Ease.Smooth)),
        ),
    )

    val PRESETS: Map<String, Preset> = linkedMapOf(
        "auto" to Preset("Авто", auto = true),
        "fade" to Preset("Затухание", "crossfade", "none", "none"),
        "rise" to Preset("Нарастание", "rise_fade", "none", "lp_in"),
        "blend" to Preset("Слияние", "slow_crossfade", "bass_swap_center", "none"),
        "wave" to Preset("Волна", "crossfade", "three_band", "lp_in_out"),
        "dissolve" to Preset("Растворение", "slow_crossfade", "bass_fade", "lp_out"),
        "hit" to Preset("Удар", "hard_swap", "bass_cut_hard", "none"),
        "melt" to Preset("Плавление", "rise_fade", "bass_cut_soft", "hp_in_lp_out"),
        "burst" to Preset("Всплеск", "cut_in_fade_out", "start_fade", "hp_out"),
        "shine" to Preset("Сияние", "rise_cut_out", "bass_swap_end", "hp_in"),
        "none" to Preset("Без перехода", "hard_swap", "none", "none"),
        "custom" to Preset("Свой вариант", custom = true),
    )

    /** Порядок карусели пресетов; «Свой вариант» вставляется первым по месту. */
    val PRESET_ORDER = listOf("auto", "fade", "rise", "blend", "wave", "dissolve", "hit", "melt", "burst", "shine", "none")

    /** Длины перехода в тактах. У Spotify только 2/4/8 — у нас шире. */
    val BAR_OPTIONS = listOf(1, 2, 4, 8, 16, 32)

    /** Цвета кривых — те же, что у меток в списках эффектов. */
    const val COLOR_VOLUME = 0xFF41ADDF
    const val COLOR_EQ = 0xFFFFD035
    const val COLOR_FILTER = 0xFFEF97FF

    /**
     * «Авто»: под совместимые темпы — честное перекрытие с заменой баса, под
     * несовместимые — длинный мягкий кроссфейд, где разница в ритме не так слышна.
     */
    fun autoShapes(beatMatched: Boolean): Shapes =
        if (beatMatched) Shapes("overlap", "bass_swap_center", "none") else Shapes("slow_crossfade", "bass_fade", "none")

    /** Разворачивает пресет в конкретную тройку форм. */
    fun resolve(preset: String?, volume: String?, eq: String?, filter: String?, beatMatched: Boolean): Shapes {
        val p = PRESETS[preset] ?: PRESETS.getValue("auto")
        if (p.auto) return autoShapes(beatMatched)
        if (p.custom || p.volume == null) {
            return Shapes(volume ?: "crossfade", eq ?: "none", filter ?: "none")
        }
        return Shapes(p.volume, p.eq ?: "none", p.filter ?: "none")
    }

    /** Темпы сходятся, если один укладывается в другой с точностью 8 %. */
    fun beatsMatch(bpmA: Double?, bpmB: Double?, tolerance: Double = 0.08): Boolean {
        if (bpmA == null || bpmB == null || bpmA == 0.0 || bpmB == 0.0) return false
        for (k in doubleArrayOf(0.5, 1.0, 2.0)) {
            if (abs(bpmA * k - bpmB) / bpmB <= tolerance) return true
        }
        return false
    }

    fun volume(id: String): VolumeShape = VOLUME[id] ?: VOLUME.getValue("crossfade")

    fun eq(id: String): EqShape = EQ[id] ?: EQ.getValue("none")

    fun filter(id: String): FilterShape = FILTER[id] ?: FILTER.getValue("none")
}
