const nf = new Intl.NumberFormat('ru-RU')

export function formatDuration(ms) {
  if (!ms || ms < 0) return '0:00'
  const total = Math.floor(ms / 1000)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

export function formatNumber(n) {
  return nf.format(n ?? 0)
}

export function formatCount(n) {
  if (n == null) return ''
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, '')} млн`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1).replace(/\.0$/, '')} тыс.`
  return String(n)
}

/** Russian plural form: plural(3, 'трек', 'трека', 'треков'). */
export function plural(n, one, few, many) {
  const abs = Math.abs(n) % 100
  const d = abs % 10
  if (abs > 10 && abs < 20) return many
  if (d > 1 && d < 5) return few
  if (d === 1) return one
  return many
}

export function trackCount(n) {
  return `${nf.format(n ?? 0)} ${plural(n ?? 0, 'трек', 'трека', 'треков')}`
}

export function formatListeners(n) {
  return `${nf.format(n ?? 0)} ${plural(n ?? 0, 'слушатель', 'слушателя', 'слушателей')} за месяц`
}

/** "47 мин. 10 сек." / "4 ч. 45 мин."; approx adds "примерно". */
export function formatTotalDuration(ms, approx = false) {
  const total = Math.round((ms || 0) / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  if (h) return approx ? `примерно ${h} ч. ${m} мин.` : `${h} ч. ${m} мин.`
  return `${m} мин. ${s} сек.`
}

/** Relative date like Spotify's "Дата добавления" column: "2 дня назад". */
export function formatRelativeDate(iso) {
  if (!iso) return ''
  const then = new Date(iso)
  const sec = Math.max(0, (Date.now() - then.getTime()) / 1000)
  if (sec < 3600) {
    const m = Math.max(1, Math.floor(sec / 60))
    return `${m} ${plural(m, 'минуту', 'минуты', 'минут')} назад`
  }
  if (sec < 86400) {
    const h = Math.floor(sec / 3600)
    return `${h} ${plural(h, 'час', 'часа', 'часов')} назад`
  }
  const days = Math.floor(sec / 86400)
  if (days < 30) return `${days} ${plural(days, 'день', 'дня', 'дней')} назад`
  return then.toLocaleDateString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' })
}
