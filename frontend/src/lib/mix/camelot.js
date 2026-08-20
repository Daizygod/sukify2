/**
 * Камелот-круг: тональность как «номер + сторона». Сторона B — мажор,
 * сторона A — минор. Соседние номера и одноимённые пары звучат вместе, поэтому
 * по этим меткам и подбирают треки для микса.
 *
 * Цвета сняты с оригинала пипеткой. Светлая сторона A — это тот же цвет,
 * разбавленный белым на 30 %, так что достаточно хранить сторону B.
 */

const B_COLORS = {
  1: '#05ECCB',
  2: '#3DED82',
  3: '#8DF25E',
  4: '#DECA73',
  5: '#FEA57C',
  6: '#FF8893',
  7: '#FF80B4',
  8: '#EB88DC',
  9: '#CB90FF',
  10: '#A0B6FF',
  11: '#51B8FB',
  12: '#0DE8EC',
}

function lighten(hex, amount = 0.3) {
  const n = parseInt(hex.slice(1), 16)
  const mix = (c) => Math.round(c * (1 - amount) + 255 * amount)
  return (
    '#' +
    [(n >> 16) & 255, (n >> 8) & 255, n & 255]
      .map((c) => mix(c).toString(16).padStart(2, '0'))
      .join('')
      .toUpperCase()
  )
}

/** Цвет плашки тональности: '9B' → '#CB90FF'. */
export function camelotColor(camelot) {
  if (!camelot) return null
  const m = /^(\d{1,2})([AB])$/i.exec(camelot.trim())
  if (!m) return null
  const base = B_COLORS[Number(m[1])]
  if (!base) return null
  return m[2].toUpperCase() === 'B' ? base : lighten(base)
}

/** Текст на плашке всегда тёмный — фон светлый во всех 24 вариантах. */
export const CAMELOT_TEXT = '#121212'

const NOTE_RU = {
  C: 'до', 'C#': 'до-диез', D: 'ре', 'D#': 'ми-бемоль', E: 'ми', F: 'фа',
  'F#': 'фа-диез', G: 'соль', 'G#': 'ля-бемоль', A: 'ля', 'A#': 'си-бемоль', B: 'си',
}

/** Подсказка к плашке: «9B · ми мажор». */
export function camelotTitle(camelot, key, scale) {
  if (!camelot) return 'Тональность не определена'
  if (!key) return camelot
  const note = NOTE_RU[key] || key
  return `${camelot} · ${note} ${scale === 'minor' ? 'минор' : 'мажор'}`
}

/**
 * Насколько дружат тональности: тот же номер, соседний номер или переход
 * между сторонами одного номера — всё это считается совместимым.
 */
export function keysCompatible(a, b) {
  const pa = /^(\d{1,2})([AB])$/i.exec((a || '').trim())
  const pb = /^(\d{1,2})([AB])$/i.exec((b || '').trim())
  if (!pa || !pb) return false
  const na = Number(pa[1])
  const nb = Number(pb[1])
  const sa = pa[2].toUpperCase()
  const sb = pb[2].toUpperCase()
  if (na === nb) return true
  const step = Math.min(Math.abs(na - nb), 12 - Math.abs(na - nb))
  return step === 1 && sa === sb
}
