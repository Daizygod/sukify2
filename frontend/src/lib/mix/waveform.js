/**
 * Волна трека: с бэка приходит base64 от массива байт 0..255 — по одному
 * значению на корзину. Здесь их разворачиваем и превращаем в путь для SVG.
 */

/** Цвета волны сняты с оригинала: полная полоса синяя, бас поверх — рыжий. */
export const WAVE_COLORS = {
  full: '#1737B1',
  bass: '#C98335',
}

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
 * Путь зеркальной волны для окна [fromMs, toMs] трека.
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
  const top = []
  const bottom = []

  for (let i = 0; i < columns; i++) {
    const x = (i / (columns - 1)) * width
    const t0 = fromMs + ((toMs - fromMs) * i) / columns
    const t1 = fromMs + ((toMs - fromMs) * (i + 1)) / columns
    // Одна колонка на экране может накрывать несколько корзин — берём максимум,
    // иначе быстрые пики просто исчезают при большом масштабе.
    const b0 = Math.max(0, Math.floor((t0 / durationMs) * peaks.length))
    const b1 = Math.min(peaks.length, Math.max(b0 + 1, Math.ceil((t1 / durationMs) * peaks.length)))
    let peak = 0
    for (let b = b0; b < b1; b++) if (peaks[b] > peak) peak = peaks[b]
    const h = (peak / 255) * mid
    top.push(`${x.toFixed(1)},${(mid - h).toFixed(1)}`)
    bottom.push(`${x.toFixed(1)},${(mid + h).toFixed(1)}`)
  }

  return `M${top.join('L')}L${bottom.reverse().join('L')}Z`
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
