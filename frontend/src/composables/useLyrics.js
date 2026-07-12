import { ref, computed, watch } from 'vue'
import api from '@/lib/api'
import { usePlayerStore } from '@/stores/player'

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

  async function load() {
    lyrics.value = null
    lines.value = []
    const t = player.currentTrack
    if (!t) return
    try {
      const { data } = await api.get(`/tracks/${t.id}/lyrics`)
      lyrics.value = data
      if (data.synced) lines.value = parseLrc(data.synced)
    } catch {
      lyrics.value = { found: false }
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
