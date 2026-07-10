import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import api from '@/lib/api'
import { getRealtime, subscriptionToken } from '@/lib/realtime'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toasts'

const STATE_INTERVAL = 5000

/**
 * Jam — совместное прослушивание: хост транслирует своё состояние в канал
 * session:{id}, участники синхронно играют то же самое у себя.
 */
export const useJamStore = defineStore('jam', () => {
  const player = usePlayerStore()
  const auth = useAuthStore()
  const toasts = useToastStore()

  const session = ref(null) // { id, join_code, members: [...] }
  const members = ref([])
  let sub = null
  let pushTimer = null
  let stopWatch = null
  let lastTrackId = null

  const active = computed(() => !!session.value)
  const isHost = computed(() => session.value && session.value.host?.id === auth.user?.id)

  async function start() {
    const { data } = await api.post('/jam/sessions')
    session.value = data.data
    members.value = data.data.members || []
    await openChannel()
    startHostBroadcast()
    toasts.show(`Jam создан! Код: ${session.value.join_code}`)
    return session.value
  }

  async function join(code) {
    const { data } = await api.post('/jam/sessions/join', { join_code: code.trim() })
    session.value = data.data
    members.value = data.data.members || []
    await openChannel()
    toasts.show('Ты в Jam! Музыка появится с ходом хоста')
  }

  async function openChannel() {
    const client = await getRealtime()
    const channel = `session:${session.value.id}`
    sub = client.newSubscription(channel, {
      getToken: () => subscriptionToken(channel),
    })
    sub.on('publication', (ctx) => handle(ctx.data))
    sub.subscribe()
  }

  function startHostBroadcast() {
    pushTimer = setInterval(() => {
      if (player.isPlaying) pushState()
    }, STATE_INTERVAL)
    stopWatch = watch(
      () => [player.currentTrack?.id, player.isPlaying],
      () => pushState()
    )
  }

  function pushState() {
    if (!sub || !isHost.value || !player.currentTrack) return
    sub.publish({
      t: 'jam-state',
      from: auth.user.id,
      trackId: player.currentTrack.id,
      queueIds: player.queue.slice(0, 300).map((x) => x.id),
      index: player.queueIndex,
      pos: Math.round(player.positionMs),
      playing: player.isPlaying,
      contextName: player.contextName,
    }).catch(() => {})
  }

  async function handle(msg) {
    if (!msg?.t && !msg?.type) return
    const type = msg.t || msg.type

    if (type === 'member.joined') {
      if (msg.user?.id !== auth.user?.id) {
        members.value = [...members.value, msg.user]
        toasts.show(`${msg.user?.name || 'Кто-то'} присоединился к Jam`)
      }
      if (isHost.value) pushState()
      return
    }
    if (type === 'member.left') {
      members.value = members.value.filter((m) => m.id !== msg.user?.id)
      return
    }
    if (type === 'session.ended') {
      cleanup()
      toasts.show('Jam завершён')
      return
    }
    if (type === 'jam-state' && !isHost.value && msg.from !== auth.user?.id) {
      await applyState(msg)
    }
  }

  /** Гость повторяет состояние хоста. */
  async function applyState(s) {
    if (player.currentTrack?.id === s.trackId) {
      // Тот же трек — подтягиваем позицию при рассинхроне > 3с.
      if (Math.abs(player.positionMs - s.pos) > 3000) player.seek(s.pos)
      if (s.playing !== player.isPlaying) player.togglePlay()
      return
    }
    lastTrackId = s.trackId
    try {
      const { data } = await api.get('/tracks-bulk', { params: { ids: s.queueIds.join(',') } })
      const byId = new Map(data.data.map((t) => [t.id, t]))
      const tracks = s.queueIds.map((id) => byId.get(id)).filter(Boolean)
      await player.hydrate({
        tracks,
        index: Math.max(tracks.findIndex((t) => t.id === s.trackId), 0),
        positionMs: s.pos,
        playing: s.playing,
        name: `Jam: ${s.contextName || ''}`,
      })
    } catch {
      /* не удалось — следующий стейт попробует снова */
    }
  }

  async function leave() {
    if (!session.value) return
    const id = session.value.id
    try {
      await api.post(`/jam/sessions/${id}/leave`)
    } catch {
      /* уже завершён */
    }
    cleanup()
  }

  function cleanup() {
    sub?.unsubscribe()
    sub = null
    clearInterval(pushTimer)
    pushTimer = null
    stopWatch?.()
    stopWatch = null
    session.value = null
    members.value = []
  }

  return { session, members, active, isHost, start, join, leave }
})
