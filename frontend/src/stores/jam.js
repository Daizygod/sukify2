import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import api from '@/lib/api'
import { getRealtime, subscriptionToken } from '@/lib/realtime'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toasts'

const STATE_INTERVAL = 5000
const STORE_KEY = 'sukify.jamSession'

/**
 * Jam — совместное прослушивание: хост транслирует своё состояние в канал
 * session:{id}, участники синхронно играют то же самое у себя.
 *
 * Тонкие места, из-за которых оно раньше «работало через раз»:
 *  - гость подписывается на канал уже после того, как сервер разослал
 *    member.joined, поэтому первый снимок хоста улетал в пустоту. Теперь
 *    гость сам здоровается (jam-hello) и вдобавок читает историю канала;
 *  - подписка молчала об ошибках, и «джем есть, а музыки нет» выглядело как
 *    магия. Теперь ошибки видно;
 *  - перезагрузка страницы выбрасывала из джема. Теперь он поднимается.
 */
export const useJamStore = defineStore('jam', () => {
  const player = usePlayerStore()
  const auth = useAuthStore()
  const toasts = useToastStore()

  const session = ref(null) // { id, join_code, host, members: [...] }
  const members = ref([])
  const connected = ref(false) // подписка на канал жива
  let sub = null
  let pushTimer = null
  let stopWatch = null
  let applying = false // пока применяем чужое состояние, своё не шлём
  let lastApplied = '' // подпись последнего принятого снимка

  const active = computed(() => !!session.value)
  const isHost = computed(() => !!session.value && session.value.host?.id === auth.user?.id)
  const host = computed(() => session.value?.host || null)

  function remember(id) {
    try {
      if (id) localStorage.setItem(STORE_KEY, String(id))
      else localStorage.removeItem(STORE_KEY)
    } catch {
      /* приватный режим */
    }
  }

  async function start() {
    const { data } = await api.post('/jam/sessions')
    await enter(data.data)
    toasts.show(`Джем создан! Код: ${session.value.join_code}`)

    return session.value
  }

  async function join(code) {
    const { data } = await api.post('/jam/sessions/join', { join_code: code.trim() })
    await enter(data.data)
    toasts.show('Ты в джеме — сейчас подхватим, что играет у хоста')
  }

  /** Общий вход: состояние, канал, рассылка (для хоста). */
  async function enter(data) {
    session.value = data
    members.value = data.members || []
    remember(data.id)
    await openChannel()
    startBroadcast()
  }

  /** После перезагрузки страницы джем должен остаться. */
  async function restore() {
    let id = null
    try {
      id = localStorage.getItem(STORE_KEY)
    } catch {
      return
    }
    if (!id || session.value) return
    try {
      const { data } = await api.get(`/jam/sessions/${id}`)
      if (!data.data?.is_active) throw new Error('inactive')
      await enter(data.data)
    } catch {
      remember(null)
    }
  }

  async function openChannel() {
    const client = await getRealtime()
    const channel = `session:${session.value.id}`
    sub = client.getSubscription(channel)
    if (!sub) {
      sub = client.newSubscription(channel, { getToken: () => subscriptionToken(channel) })
    }
    sub.on('publication', (ctx) => handle(ctx.data))
    sub.on('subscribed', () => {
      connected.value = true
      onSubscribed()
    })
    sub.on('unsubscribed', () => (connected.value = false))
    sub.on('error', (ctx) => {
      connected.value = false
      // Молчать нельзя: снаружи это выглядит как «джем есть, музыки нет».
      console.warn('[jam] канал недоступен', ctx)
      toasts.show('Джем не может подключиться к серверу — музыка не синхронизируется')
    })
    if (sub.state !== 'subscribed') sub.subscribe()
    else onSubscribed()
  }

  /** Подписались: хост сразу шлёт снимок, гость просит его и смотрит историю. */
  async function onSubscribed() {
    if (isHost.value) {
      pushState()

      return
    }
    try {
      const h = await sub.history({ limit: 20, reverse: true })
      const last = (h.publications || [])
        .map((p) => p.data)
        .find((d) => (d?.t || d?.type) === 'jam-state')
      if (last) await applyState(last)
    } catch {
      /* истории может не быть — переживём, хост ответит на привет */
    }
    publish({ t: 'jam-hello', from: auth.user?.id })
  }

  function startBroadcast() {
    clearInterval(pushTimer)
    // Мерное «я здесь, играю вот это» шлёт только хост: если так делать
    // каждому, участники начнут перетягивать позицию друг у друга.
    pushTimer = setInterval(() => {
      if (isHost.value) pushState()
    }, STATE_INTERVAL)
    stopWatch?.()
    // А вот нажатия — от кого угодно: в джеме рулить может любой, как в
    // оригинале. Перемотку ловим по счётчику: позиция меняется каждый кадр.
    stopWatch = watch(
      () => [player.currentTrack?.id, player.isPlaying, player.seekTick],
      () => pushState()
    )
  }

  function publish(payload) {
    if (!sub) return Promise.resolve()

    return sub.publish(payload).catch((e) => console.warn('[jam] не отправилось', payload.t, e))
  }

  /** Подпись состояния: по ней отличаем «мы это только что применили» от своего. */
  function signature(s) {
    return `${s.trackId}:${s.playing ? 1 : 0}:${Math.round(s.pos / 2000)}`
  }

  function pushState() {
    if (!session.value || applying || !player.currentTrack) return
    const state = {
      t: 'jam-state',
      from: auth.user.id,
      trackId: player.currentTrack.id,
      queueIds: player.queue.slice(0, 300).map((x) => x.id),
      index: player.queueIndex,
      pos: Math.round(player.positionMs),
      playing: player.isPlaying,
      contextName: player.contextName,
    }
    // Эхо гасим здесь: применили чужое состояние — своё точно такое же
    // отправлять не нужно, иначе двое зациклятся на одном треке.
    if (signature(state) === lastApplied) return
    publish(state)
  }

  async function handle(msg) {
    const type = msg?.t || msg?.type
    if (!type) return

    if (type === 'member.joined') {
      if (msg.user?.id !== auth.user?.id) {
        toasts.show(`${msg.user?.name || 'Кто-то'} присоединился к джему`)
      }
      refreshMembers()
      if (isHost.value) pushState()

      return
    }
    if (type === 'member.left') {
      refreshMembers()

      return
    }
    if (type === 'session.ended') {
      cleanup()
      toasts.show('Джем завершён')

      return
    }
    // Гость поздоровался — хост отвечает снимком, иначе тот будет ждать
    // следующего тика впустую.
    if (type === 'jam-hello') {
      if (isHost.value && msg.from !== auth.user?.id) pushState()

      return
    }
    if (type === 'jam-state' && msg.from !== auth.user?.id) {
      await applyState(msg)
    }
  }

  /** Список участников тянем с сервера: он и есть источник правды. */
  async function refreshMembers() {
    if (!session.value) return
    try {
      const { data } = await api.get(`/jam/sessions/${session.value.id}`)
      session.value = data.data
      members.value = data.data.members || []
    } catch {
      /* сеть моргнула — следующий раз поправит */
    }
  }

  /** Повторяем то, что включил другой участник. */
  async function applyState(s) {
    if (applying) return
    applying = true
    lastApplied = signature(s)
    try {
      if (player.currentTrack?.id === s.trackId) {
        // Тот же трек — подтягиваем позицию при рассинхроне > 3 с.
        if (Math.abs(player.positionMs - s.pos) > 3000) player.seek(s.pos)
        if (s.playing !== player.isPlaying) player.togglePlay()

        return
      }
      const ids = s.queueIds?.length ? s.queueIds : [s.trackId]
      const { data } = await api.get('/tracks-bulk', { params: { ids: ids.join(',') } })
      const byId = new Map(data.data.map((t) => [t.id, t]))
      const tracks = ids.map((id) => byId.get(id)).filter(Boolean)
      if (!tracks.length) return
      await player.hydrate({
        tracks,
        index: Math.max(tracks.findIndex((t) => t.id === s.trackId), 0),
        positionMs: s.pos,
        playing: s.playing,
        name: `Джем: ${s.contextName || ''}`,
      })
      // Браузер имеет право не пустить звук без нажатия — скажем об этом
      // прямо, иначе выглядит как поломка.
      if (s.playing && !player.isPlaying) {
        toasts.show('Нажми ▶ — браузер не пускает звук без твоего действия')
      }
    } catch (e) {
      console.warn('[jam] не удалось повторить состояние хоста', e)
    } finally {
      applying = false
    }
  }

  /** Гость выходит, хост завершает джем для всех. */
  async function leave() {
    if (!session.value) return
    const id = session.value.id
    const wasHost = isHost.value
    try {
      await api.post(`/jam/sessions/${id}/${wasHost ? 'end' : 'leave'}`)
    } catch {
      /* уже завершён */
    }
    cleanup()
    toasts.show(wasHost ? 'Джем завершён' : 'Ты вышел из джема')
  }

  function cleanup() {
    sub?.removeAllListeners?.()
    sub?.unsubscribe()
    sub = null
    clearInterval(pushTimer)
    pushTimer = null
    stopWatch?.()
    stopWatch = null
    connected.value = false
    session.value = null
    members.value = []
    remember(null)
  }

  return { session, members, active, isHost, host, connected, start, join, leave, restore }
})
