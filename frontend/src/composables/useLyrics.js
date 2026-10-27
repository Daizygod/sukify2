import { ref, computed, watch } from 'vue'
import api from '@/lib/api'
import { usePlayerStore } from '@/stores/player'

// Текст нужен сразу нескольким местам (панель справа, окно текста, полный
// экран) — один запрос на трек, ответ живёт десять минут.
const cache = new Map() // id → { at, promise }
const TTL = 10 * 60 * 1000
function fetchLyrics(id) {
  const hit = cache.get(id)
  if (hit && Date.now() - hit.at < TTL) return hit.promise
  const promise = api.get(`/tracks/${id}/lyrics`).then(({ data }) => data)
  promise.catch(() => cache.delete(id))
  cache.set(id, { at: Date.now(), promise })
  if (cache.size > 60) cache.delete(cache.keys().next().value)
  return promise
}

/**
 * Текст текущего трека: загрузка, парсинг LRC и активная строка по позиции.
 * Используется в панели «Текст» и в полноэкранном режиме.
 */
export function useLyrics() {
  const player = usePlayerStore()

  const lyrics = ref(null) // { found, synced, plain }
  const lines = ref([]) // [{ ms, text }]

  function parseLrc(lrc) {
    const out = []
    for (const raw of lrc.split('\n')) {
      const stamps = [...raw.matchAll(/\[(\d+):(\d+(?:\.\d+)?)\]/g)]
      if (!stamps.length) continue
      const text = raw.replace(/\[.*?\]/g, '').trim()
      for (const s of stamps) {
        out.push({ ms: (Number(s[1]) * 60 + Number(s[2])) * 1000, text })
      }
    }
    return out.sort((a, b) => a.ms - b.ms)
  }

  // При быстром переключении треков поздний ответ старого запроса не должен
  // затирать текст нового — сверяем порядковый номер запроса.
  let seq = 0
  async function load() {
    const my = ++seq
    lyrics.value = null
    lines.value = []
    const t = player.currentTrack
    if (!t) return
    try {
      const data = await fetchLyrics(t.id)
      if (my !== seq) return // уже переключились на другой трек
      lyrics.value = data
      if (data.synced) lines.value = parseLrc(data.synced)
    } catch {
      if (my === seq) lyrics.value = { found: false }
    }
  }
  watch(() => player.currentTrack?.id, load, { immediate: true })

  const activeIndex = computed(() => {
    const pos = player.positionMs
    let idx = -1
    for (let i = 0; i < lines.value.length; i++) {
      if (lines.value[i].ms <= pos) idx = i
      else break
    }
    return idx
  })

  const hasLyrics = computed(() => !!(lines.value.length || lyrics.value?.plain))

  return { lyrics, lines, activeIndex, hasLyrics }
}
