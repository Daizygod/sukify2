/**
 * Build a gain-envelope value array from `from` to `to` following one of the
 * transition curve shapes. Fed to AudioParam.setValueCurveAtTime.
 */
export function buildCurve(from, to, type = 'linear', steps = 64) {
  const values = []
  const descending = from > to
  for (let i = 0; i < steps; i++) {
    const t = i / (steps - 1) // 0..1
    const shaped = shape(t, type, descending)
    values.push(from + (to - from) * shaped)
  }
  return values
}

function shape(t, type, descending = false) {
  switch (type) {
    case 'equal_power':
      // Кроссфейд «равной мощности», как у Spotify: слышимая суммарная
      // громкость постоянна. Затухание идёт по косинусу, нарастание — по
      // синусу (cos²+sin²=1, провала в середине нет).
      return descending ? 1 - Math.cos((t * Math.PI) / 2) : Math.sin((t * Math.PI) / 2)
    case 'exponential':
      // slow then fast
      return t * t
    case 'logarithmic':
      // fast then slow
      return Math.sqrt(t)
    case 's_curve':
      // smoothstep
      return t * t * (3 - 2 * t)
    case 'linear':
    default:
      return t
  }
}
