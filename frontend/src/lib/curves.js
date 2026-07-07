/**
 * Build a gain-envelope value array from `from` to `to` following one of the
 * transition curve shapes. Fed to AudioParam.setValueCurveAtTime.
 */
export function buildCurve(from, to, type = 'linear', steps = 64) {
  const values = []
  for (let i = 0; i < steps; i++) {
    const t = i / (steps - 1) // 0..1
    const shaped = shape(t, type)
    values.push(from + (to - from) * shaped)
  }
  return values
}

function shape(t, type) {
  switch (type) {
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
