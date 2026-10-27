/**
 * Куда ведёт название играющего трека: как в Spotify — на его альбом или
 * сингл, где строка трека подсвечивается (?highlight=id).
 *
 * slug берём у самого трека; если его нет (старая сессия из localStorage,
 * пульт Connect присылает releaseSlug отдельным полем) — из ключа контекста,
 * когда играет именно релиз.
 */
export function releaseRoute(track, contextKey = '') {
  if (!track) return null
  const slug =
    track.release?.slug ||
    track.releaseSlug ||
    (contextKey.startsWith('release:') ? contextKey.slice('release:'.length) : null)
  if (!slug) return null
  return {
    name: 'release',
    params: { slug },
    query: track.id ? { highlight: String(track.id) } : {},
  }
}

/** Страница играющей подборки по ключу контекста ('release:slug', 'liked'…). */
export function contextRoute(key) {
  if (!key) return null
  if (key === 'liked') return { name: 'liked' }
  const at = key.indexOf(':')
  if (at < 0) return null
  const kind = key.slice(0, at)
  const v = key.slice(at + 1)
  if (!v) return null
  switch (kind) {
    case 'release':
      return { name: 'release', params: { slug: v } }
    case 'playlist':
      return { name: 'playlist', params: { id: v } }
    case 'artist':
      return { name: 'artist', params: { slug: v } }
    case 'artist-liked':
      return { name: 'artist-liked', params: { slug: v } }
    case 'mix':
      return { name: 'mix', params: { n: v } }
    default:
      return null
  }
}
