import { ref } from 'vue'
import api from '@/lib/api'

/** Batch-load the best-transition summary for consecutive pairs of a tracklist. */
export function useTransitionInfo() {
  const info = ref({})

  async function load(tracks) {
    const pairs = []
    for (let i = 0; i < tracks.length - 1; i++) {
      pairs.push(`${tracks[i].id}:${tracks[i + 1].id}`)
    }
    if (!pairs.length) {
      info.value = {}
      return
    }
    try {
      const { data } = await api.get('/transitions/for-context', {
        params: { pairs: pairs.join(',') },
      })
      info.value = data.data || {}
    } catch {
      info.value = {}
    }
  }

  const keyFor = (a, b) => `${a.id}:${b.id}`

  return { info, load, keyFor }
}
