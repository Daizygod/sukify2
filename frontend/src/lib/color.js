/** '#rgb' / '#rrggbb' → [r, g, b] (0..255) или null. */
export function parseHex(hex) {
  const m = /^#?([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(String(hex || '').trim())
  if (!m) return null
  let s = m[1]
  if (s.length === 3) s = s.replace(/./g, (c) => c + c)
  const n = parseInt(s, 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

function rgbToHsl([r, g, b]) {
  r /= 255
  g /= 255
  b /= 255
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const l = (max + min) / 2
  if (max === min) return [0, 0, l]
  const d = max - min
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
  let h
  if (max === r) h = (g - b) / d + (g < b ? 6 : 0)
  else if (max === g) h = (b - r) / d + 2
  else h = (r - g) / d + 4
  return [h * 60, s, l]
}

function hslToHex(h, s, l) {
  const a = s * Math.min(l, 1 - l)
  const f = (n) => {
    const k = (n + h / 30) % 12
    const c = l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))
    return Math.round(c * 255)
      .toString(16)
      .padStart(2, '0')
  }
  return `#${f(0)}${f(8)}${f(4)}`
}

/**
 * Светлый оттенок цвета обложки: тот же тон, высокая светлота. Так Spotify
 * красит ещё не спетые строки текста на цветном фоне.
 */
export function tint(hex, lightness = 0.84) {
  const rgb = parseHex(hex)
  if (!rgb) return '#d9d9d9'
  const [h, s] = rgbToHsl(rgb)
  return hslToHex(h, Math.min(s, 0.9), lightness)
}

/** Тот же цвет темнее/светлее (lightness 0..1) — для глубины градиентов. */
export function withLightness(hex, lightness) {
  const rgb = parseHex(hex)
  if (!rgb) return hex
  const [h, s] = rgbToHsl(rgb)
  return hslToHex(h, s, Math.max(0, Math.min(1, lightness)))
}
