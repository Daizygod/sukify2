/**
 * Волна трека. С бэка приходит base64 от массива байт 0..255 — по одному
 * значению на корзину.
 *
 * Новый формат — три полосы (низ, середина, верх), у каждой пара: тело (RMS)
 * и гребень (максимум). Рисуем их стопкой от середины: у зажатого мастера
 * пики стоят у потолка все до одного, и одна полоса превращается в кирпич, а
 * три — показывают, чем трек набит. Старый формат (полная полоса + бас)
 * остаётся для треков, которые ещё не пересчитаны.
 */

/** Цвета сняты пипеткой с оригинала: низ синий, середина рыжая, верх белый. */
export const WAVE_COLORS = {
  full: '#1737B1',
  bass: '#C98335',
}

export const BAND_COLORS = {
  lows: { rms: '#1737B1', peak: '#1737B1' },
  mids: { rms: '#844B1F', peak: '#C98335' },
  highs: { rms: '#F3E6D0', peak: '#EDFFFF' },
}

/** Порядок отрисовки: снаружи внутрь, каждый слой ложится поверх прошлого. */
const LAYERS = [
  { key: 'lows-peak', band: 'lows', part: 'peak' },
  { key: 'lows-rms', band: 'lows', part: 'rms' },
  { key: 'mids-peak', band: 'mids', part: 'peak' },
  { key: 'mids-rms', band: 'mids', part: 'rms' },
  { key: 'highs-peak', band: 'highs', part: 'peak' },
  { key: 'highs-rms', band: 'highs', part: 'rms' },
]

/** base64 → Uint8Array. Пустая строка даёт пустой массив, а не исключение. */
export function decodePeaks(b64) {
  if (!b64) return new Uint8Array(0)
  try {
    const bin = atob(b64)
    const out = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i)
    return out
  } catch {
    return new Uint8Array(0)
  }
}

/**
 * Насколько высоко гребень поднимается над телом полосы. Без ограничения
 * рисовать нечего: у зажатого мастера максимум стоит у потолка в каждой
 * корзине, сумма полос выходит постоянной и волна превращается в кирпич.
 * С ограничением высоту ведёт RMS — то, что действительно ходит, — а гребень
 * остаётся признаком удара.
 */
const CREST_LIMIT = 0.55

function crestOf(rms, peak) {
  return Math.min(Math.max(0, peak - rms), rms * CREST_LIMIT)
}

/** Три полосы из ответа анализа. null, если трек ещё не пересчитан. */
export function decodeBands(bands) {
  if (!bands?.lows?.peak) return null
  const out = {}
  for (const band of ['lows', 'mids', 'highs']) {
    out[band] = {
      rms: decodePeaks(bands[band]?.rms),
      peak: decodePeaks(bands[band]?.peak),
    }
  }
  const count = out.lows.peak.length
  if (!count) return null

  // Масштаб общий на весь трек: самая громкая корзина занимает панель целиком,
  // остальные — ровно столько, сколько весят рядом с ней.
  let scale = 1
  for (let i = 0; i < count; i++) {
    let total = 0
    for (const band of ['lows', 'mids', 'highs']) {
      const rms = out[band].rms[i]
      total += rms + crestOf(rms, out[band].peak[i])
    }
    if (total > scale) scale = total
  }
  out.scale = scale

  return out
}

/** Границы корзин, попадающих в колонку [t0, t1] трека длиной durationMs. */
function bucketRange(t0, t1, durationMs, count) {
  const b0 = Math.floor((t0 / durationMs) * count)
  const b1 = Math.ceil((t1 / durationMs) * count)

  return [Math.max(0, b0), Math.min(count, Math.max(b0 + 1, b1))]
}

/** Максимум по корзинам колонки: иначе быстрые удары исчезают при отдалении. */
function peakIn(values, b0, b1) {
  let top = 0
  for (let b = b0; b < b1; b++) if (values[b] > top) top = values[b]

  return top
}

/**
 * Шесть путей стопкой: низ снаружи, верх у самой середины.
 * Колонки за пределами трека пропускаем — волна должна кончаться там же,
 * где кончается звук, а не размазываться первой корзиной до края панели.
 *
 * @returns {{ key: string, color: string, d: string }[]} снаружи внутрь
 */
export function bandPaths(bands, durationMs, fromMs, toMs, width, height, columns = 260) {
  const count = bands?.lows?.peak?.length || 0
  if (!count || !durationMs || toMs <= fromMs) return []

  const mid = height / 2
  const span = toMs - fromMs
  // Десятая часть высоты остаётся свободной: волна, упирающаяся в края
  // панели, читается как обрезанная, да и гребню нужно куда расти.
  const scale = (bands.scale || 255) / 0.9
  // Для каждой колонки — накопленные полувысоты слоёв, снаружи внутрь.
  const stack = []
  for (let i = 0; i < columns; i++) {
    const t0 = fromMs + (span * i) / columns
    const t1 = fromMs + (span * (i + 1)) / columns
    const x = (i / (columns - 1)) * width
    if (t1 <= 0 || t0 >= durationMs) {
      stack.push({ x, h: null })
      continue
    }
    const [b0, b1] = bucketRange(Math.max(0, t0), Math.min(durationMs, t1), durationMs, count)
    const v = {}
    for (const band of ['lows', 'mids', 'highs']) {
      const peak = peakIn(bands[band].peak, b0, b1)
      // После округления до байта тело изредка обгоняет гребень — подрезаем,
      // иначе слой вылезает наружу и полосы меняются местами.
      const rms = Math.min(peak, peakIn(bands[band].rms, b0, b1))
      v[band] = { rms, top: rms + crestOf(rms, peak) }
    }
    const h = {
      'highs-rms': v.highs.rms,
      'highs-peak': v.highs.top,
      'mids-rms': v.highs.top + v.mids.rms,
      'mids-peak': v.highs.top + v.mids.top,
      'lows-rms': v.highs.top + v.mids.top + v.lows.rms,
      'lows-peak': v.highs.top + v.mids.top + v.lows.top,
    }
    stack.push({ x, h })
  }

  return LAYERS.map(({ key, band, part }) => {
    const top = []
    const bottom = []
    let open = false
    for (const col of stack) {
      if (col.h === null) {
        open = false
        continue
      }
      // Разрыв в данных начинает новый кусок пути, а не тянет линию через него.
      const y = Math.min(1, col.h[key] / scale) * mid
      if (!open) {
        top.push(`M${col.x.toFixed(1)},${(mid - y).toFixed(1)}`)
        open = true
      } else {
        top.push(`L${col.x.toFixed(1)},${(mid - y).toFixed(1)}`)
      }
      bottom.unshift(`L${col.x.toFixed(1)},${(mid + y).toFixed(1)}`)
    }
    if (!top.length) return { key, color: BAND_COLORS[band][part], d: '' }

    return { key, color: BAND_COLORS[band][part], d: `${top.join('')}${bottom.join('')}Z` }
  })
}

/**
 * Путь зеркальной волны для окна [fromMs, toMs] трека — старый формат.
 *
 * @param {Uint8Array} peaks   пики по всему треку
 * @param {number} durationMs  длина трека
 * @param {number} fromMs      начало окна
 * @param {number} toMs        конец окна
 * @param {number} width       ширина области в пикселях
 * @param {number} height      высота области
 * @param {number} columns     сколько столбиков рисуем
 */
export function wavePath(peaks, durationMs, fromMs, toMs, width, height, columns = 220) {
  if (!peaks.length || !durationMs || toMs <= fromMs) return ''
  const mid = height / 2
  const span = toMs - fromMs
  const top = []
  const bottom = []
  let open = false

  for (let i = 0; i < columns; i++) {
    const x = (i / (columns - 1)) * width
    const t0 = fromMs + (span * i) / columns
    const t1 = fromMs + (span * (i + 1)) / columns
    if (t1 <= 0 || t0 >= durationMs) {
      open = false
      continue
    }
    const [b0, b1] = bucketRange(Math.max(0, t0), Math.min(durationMs, t1), durationMs, peaks.length)
    const h = (peakIn(peaks, b0, b1) / 255) * mid
    top.push(`${open ? 'L' : 'M'}${x.toFixed(1)},${(mid - h).toFixed(1)}`)
    bottom.unshift(`L${x.toFixed(1)},${(mid + h).toFixed(1)}`)
    open = true
  }
  if (!top.length) return ''

  return `${top.join('')}${bottom.join('')}Z`
}

/**
 * Доли и сильные доли внутри окна.
 * Такт считаем четырёхдольным — как и сам редактор переходов Spotify.
 */
export function beatLines(beats, fromMs, toMs, width, beatsPerBar = 4) {
  if (!beats?.length || toMs <= fromMs) return []
  const out = []
  for (let i = 0; i < beats.length; i++) {
    const ms = beats[i]
    if (ms < fromMs) continue
    if (ms > toMs) break
    out.push({
      x: ((ms - fromMs) / (toMs - fromMs)) * width,
      strong: i % beatsPerBar === 0,
    })
  }

  return out
}

/** Длина такта в миллисекундах при данном темпе. */
export function barMs(bpm, beatsPerBar = 4) {
  if (!bpm) return 0
  return (60000 / bpm) * beatsPerBar
}

/** Ближайшая доля к моменту ms — по ней «прилипают» границы перекрытия. */
export function snapToBeat(beats, ms) {
  if (!beats?.length) return ms
  let best = beats[0]
  let bestDiff = Math.abs(beats[0] - ms)
  for (const b of beats) {
    const d = Math.abs(b - ms)
    if (d < bestDiff) {
      bestDiff = d
      best = b
    }
  }

  return best
}
