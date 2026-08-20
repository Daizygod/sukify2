/**
 * Формы кривых перехода: громкость, эквалайзер, фильтр.
 *
 * Одна и та же таблица кормит и редактор (он рисует кривые поверх волны), и
 * плеер (он гонит их в AudioParam). Время везде нормализовано: t = 0 — начало
 * перекрытия, t = 1 — конец, поэтому форма не зависит от длины перехода.
 *
 * Точка — [t, значение, тип подхода к ней]:
 *   'lin'    — прямая
 *   'smooth' — smoothstep, мягко с обеих сторон
 *   'in'     — медленно в начале (t²)
 *   'out'    — быстро в начале (√t)
 *   'cos'    — четверть косинуса: пара cos/sin даёт кроссфейд равной мощности
 *   'sin'    — четверть синуса
 *   'step'   — мгновенно, без интерполяции
 */

// --- Разбор точек ----------------------------------------------------------

const EASINGS = {
  lin: (t) => t,
  smooth: (t) => t * t * (3 - 2 * t),
  in: (t) => t * t,
  out: (t) => Math.sqrt(t),
  cos: (t) => 1 - Math.cos((t * Math.PI) / 2),
  sin: (t) => Math.sin((t * Math.PI) / 2),
  step: () => 1,
}

/** Значение кусочно-заданной кривой в момент t (0..1). */
export function valueAt(points, t) {
  if (!points || !points.length) return 0
  if (t <= points[0][0]) return points[0][1]
  for (let i = 1; i < points.length; i++) {
    const [t1, v1, ease = 'lin'] = points[i]
    if (t > t1) continue
    const [t0, v0] = points[i - 1]
    if (t1 === t0) return v1
    if (ease === 'step') return t < t1 ? v0 : v1
    const k = (EASINGS[ease] || EASINGS.lin)((t - t0) / (t1 - t0))
    return v0 + (v1 - v0) * k
  }
  return points[points.length - 1][1]
}

/** Равномерная выборка кривой — то, что ждёт setValueCurveAtTime. */
export function sample(points, steps = 128) {
  const out = new Float32Array(steps)
  for (let i = 0; i < steps; i++) out[i] = valueAt(points, i / (steps - 1))
  return out
}

// --- Громкость -------------------------------------------------------------
// Значение — коэффициент усиления 0..1.

export const VOLUME_SHAPES = {
  crossfade: {
    title: 'Плавный переход между треками',
    out: [[0, 1], [1, 0, 'cos']],
    in: [[0, 0], [1, 1, 'sin']],
  },
  slow_crossfade: {
    title: 'Медленный плавный переход между треками',
    out: [[0, 1], [0.25, 0.95, 'smooth'], [1, 0, 'smooth']],
    in: [[0, 0], [0.75, 0.95, 'smooth'], [1, 1, 'smooth']],
  },
  overlap: {
    title: 'Перекрытие',
    // Оба трека звучат в полную силу, уходящий обрывается в конце.
    out: [[0, 1], [0.97, 1], [1, 0, 'lin']],
    in: [[0, 1], [1, 1]],
  },
  rise_fade: {
    title: 'Усиление + затухание звука',
    out: [[0, 1], [0.4, 0.9, 'smooth'], [1, 0, 'in']],
    in: [[0, 0], [1, 1, 'in']],
  },
  cut_in_fade_out: {
    title: 'Резкий вход + затухание звука',
    out: [[0, 1], [1, 0, 'smooth']],
    in: [[0, 1, 'step'], [1, 1]],
  },
  rise_cut_out: {
    title: 'Усиление звука + резкий выход',
    out: [[0, 1], [0.97, 1], [1, 0, 'step']],
    in: [[0, 0], [1, 1, 'smooth']],
  },
  hard_swap: {
    title: 'Резкий переход в середине',
    out: [[0, 1], [0.5, 1], [0.5, 0, 'step'], [1, 0]],
    in: [[0, 0], [0.5, 0], [0.5, 1, 'step'], [1, 1]],
  },
  rise_fast_out: {
    title: 'Нарастание + быстрый выход',
    out: [[0, 1], [0.35, 0, 'in']],
    in: [[0, 0], [1, 1, 'smooth']],
  },
}

// --- Эквалайзер ------------------------------------------------------------
// Значение — усиление полосы в децибелах. Полосы: low (<200 Гц),
// mid (200 Гц … 4 кГц), high (>4 кГц). CUT — «полоса выключена».

const CUT = -40

export const EQ_SHAPES = {
  none: { title: 'Нет', out: {}, in: {} },
  bass_swap_center: {
    title: 'Замена баса в центре',
    // Бас в каждый момент только у одного трека — иначе низ мутнеет.
    out: { low: [[0, 0], [0.5, 0], [0.55, CUT, 'lin'], [1, CUT]] },
    in: { low: [[0, CUT], [0.5, CUT], [0.55, 0, 'lin'], [1, 0]] },
  },
  bass_swap_end: {
    title: 'Замена баса в конце',
    out: { low: [[0, 0], [0.85, 0], [0.9, CUT, 'lin'], [1, CUT]] },
    in: { low: [[0, CUT], [0.85, CUT], [0.9, 0, 'lin'], [1, 0]] },
  },
  bass_swap_start: {
    title: 'Замена баса в начале',
    out: { low: [[0, 0], [0.1, 0], [0.15, CUT, 'lin'], [1, CUT]] },
    in: { low: [[0, CUT], [0.1, CUT], [0.15, 0, 'lin'], [1, 0]] },
  },
  three_band: {
    title: 'Трёхполосное затухание',
    out: {
      low: [[0, 0], [0.6, CUT, 'smooth']],
      mid: [[0, 0], [0.8, CUT, 'smooth']],
      high: [[0, 0], [1, CUT, 'smooth']],
    },
    in: {
      low: [[0, CUT], [0.6, 0, 'smooth']],
      mid: [[0, CUT], [0.4, 0, 'smooth']],
      high: [[0, CUT], [0.2, 0, 'smooth']],
    },
  },
  bass_cut_hard: {
    title: 'Резкий срез баса',
    out: { low: [[0, 0], [0.02, CUT, 'step'], [1, CUT]] },
    in: { low: [[0, CUT], [0.98, CUT], [1, 0, 'step']] },
  },
  bass_cut_soft: {
    title: 'Плавный срез баса',
    out: { low: [[0, 0], [1, CUT, 'smooth']] },
    in: { low: [[0, CUT], [1, 0, 'smooth']] },
  },
  start_fade: {
    title: 'Начать затухание звука',
    out: { low: [[0, 0], [1, -24, 'in']], mid: [[0, 0], [1, -18, 'in']], high: [[0, 0], [1, -12, 'in']] },
    in: {},
  },
  bass_fade: {
    title: 'Затухание баса',
    out: { low: [[0, 0], [1, CUT, 'in']] },
    in: { low: [[0, CUT], [1, 0, 'out']] },
  },
}

// --- Фильтр ----------------------------------------------------------------
// Значение — частота среза в герцах. type: 'lowpass' | 'highpass'.

const OPEN_LP = 20000
const OPEN_HP = 20

export const FILTER_SHAPES = {
  none: { title: 'Нет', out: null, in: null },
  lp_out: {
    title: 'Выход через НЧ-фильтр',
    out: { type: 'lowpass', points: [[0, OPEN_LP], [1, 250, 'in']] },
    in: null,
  },
  lp_in: {
    title: 'Вход через НЧ-фильтр',
    out: null,
    in: { type: 'lowpass', points: [[0, 250], [1, OPEN_LP, 'out']] },
  },
  lp_in_out: {
    title: 'Вход и выход через НЧ-фильтр',
    out: { type: 'lowpass', points: [[0, OPEN_LP], [1, 250, 'in']] },
    in: { type: 'lowpass', points: [[0, 250], [1, OPEN_LP, 'out']] },
  },
  lp_in_hp_out: {
    title: 'Вход через НЧ-фильтр, выход через ВЧ-фильтр',
    out: { type: 'highpass', points: [[0, OPEN_HP], [1, 6000, 'in']] },
    in: { type: 'lowpass', points: [[0, 250], [1, OPEN_LP, 'out']] },
  },
  hp_out: {
    title: 'Выход через ВЧ-фильтр',
    out: { type: 'highpass', points: [[0, OPEN_HP], [1, 6000, 'in']] },
    in: null,
  },
  hp_in: {
    title: 'Вход через ВЧ-фильтр',
    out: null,
    in: { type: 'highpass', points: [[0, 6000], [1, OPEN_HP, 'out']] },
  },
  hp_in_out: {
    title: 'Вход и выход через ВЧ-фильтр',
    out: { type: 'highpass', points: [[0, OPEN_HP], [1, 6000, 'in']] },
    in: { type: 'highpass', points: [[0, 6000], [1, OPEN_HP, 'out']] },
  },
  hp_in_lp_out: {
    title: 'Вход через ВЧ-фильтр, выход через НЧ-фильтр',
    out: { type: 'lowpass', points: [[0, OPEN_LP], [1, 250, 'in']] },
    in: { type: 'highpass', points: [[0, 6000], [1, OPEN_HP, 'out']] },
  },
  hp_out_half: {
    title: 'Выход через ВЧ-фильтр половинной мощности',
    out: { type: 'highpass', points: [[0, OPEN_HP], [1, 1800, 'in']] },
    in: null,
  },
  noise_fade_end: {
    title: 'Шум + затухание в конце',
    out: { type: 'lowpass', points: [[0, OPEN_LP], [0.7, OPEN_LP], [1, 400, 'in']] },
    in: null,
    // Отдельный источник белого шума поверх перехода — его подмешивает плеер.
    noise: [[0, 0], [0.7, 0], [0.9, 0.18, 'smooth'], [1, 0, 'smooth']],
  },
}

// --- Пресеты ---------------------------------------------------------------
// Готовая тройка кривых. «Свой вариант» появляется сам, как только тронешь
// любой из трёх списков или сдвинешь границы перекрытия.

export const PRESETS = {
  auto: { title: 'Авто', auto: true },
  fade: { title: 'Затухание', volume: 'crossfade', eq: 'none', filter: 'none' },
  rise: { title: 'Нарастание', volume: 'rise_fade', eq: 'none', filter: 'lp_in' },
  blend: { title: 'Слияние', volume: 'slow_crossfade', eq: 'bass_swap_center', filter: 'none' },
  wave: { title: 'Волна', volume: 'crossfade', eq: 'three_band', filter: 'lp_in_out' },
  dissolve: { title: 'Растворение', volume: 'slow_crossfade', eq: 'bass_fade', filter: 'lp_out' },
  hit: { title: 'Удар', volume: 'hard_swap', eq: 'bass_cut_hard', filter: 'none' },
  melt: { title: 'Плавление', volume: 'rise_fade', eq: 'bass_cut_soft', filter: 'hp_in_lp_out' },
  burst: { title: 'Всплеск', volume: 'cut_in_fade_out', eq: 'start_fade', filter: 'hp_out' },
  shine: { title: 'Сияние', volume: 'rise_cut_out', eq: 'bass_swap_end', filter: 'hp_in' },
  none: { title: 'Без перехода', volume: 'hard_swap', eq: 'none', filter: 'none' },
  custom: { title: 'Свой вариант', custom: true },
}

/** Порядок карусели пресетов; «Свой вариант» вставляется первым по месту. */
export const PRESET_ORDER = [
  'auto', 'fade', 'rise', 'blend', 'wave', 'dissolve', 'hit', 'melt', 'burst', 'shine', 'none',
]

/**
 * «Авто»: под совместимые темпы — честное перекрытие с заменой баса, под
 * несовместимые — длинный мягкий кроссфейд, где разница в ритме не так слышна.
 */
export function autoShapes(beatMatched) {
  return beatMatched
    ? { volume: 'overlap', eq: 'bass_swap_center', filter: 'none' }
    : { volume: 'slow_crossfade', eq: 'bass_fade', filter: 'none' }
}

/** Разворачивает пресет в конкретную тройку форм. */
export function resolveShapes(transition, beatMatched = false) {
  const preset = PRESETS[transition?.preset] || PRESETS.auto
  if (preset.auto) return autoShapes(beatMatched)
  if (preset.custom || !preset.volume) {
    return {
      volume: transition?.volume_shape || 'crossfade',
      eq: transition?.eq_shape || 'none',
      filter: transition?.filter_shape || 'none',
    }
  }
  return { volume: preset.volume, eq: preset.eq, filter: preset.filter }
}

/** Список тактов для длины перехода. У Spotify только 2/4/8 — у нас шире. */
export const BAR_OPTIONS = [1, 2, 4, 8, 16, 32]

/** Темпы считаем сходящимися, если один укладывается в другой с точностью 8 %. */
export function beatsMatch(bpmA, bpmB, tolerance = 0.08) {
  if (!bpmA || !bpmB) return false
  for (const k of [0.5, 1, 2]) {
    if (Math.abs(bpmA * k - bpmB) / bpmB <= tolerance) return true
  }
  return false
}

/** Цвета кривых — те же, что у меток в списках эффектов. */
export const CURVE_COLORS = {
  volume: '#41addf',
  eq: '#ffd035',
  filter: '#ef97ff',
}
