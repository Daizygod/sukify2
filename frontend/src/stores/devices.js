import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import api from '@/lib/api'
import { getRealtime } from '@/lib/realtime'
import { usePlayerStore } from '@/stores/player'
import { useToastStore } from '@/stores/toasts'

const HELLO_INTERVAL = 10_000
const STATE_INTERVAL = 5_000
const DEVICE_TTL = 30_000

function makeDeviceId() {
  let id = sessionStorage.getItem('device.id')
  if (!id) {
    id = crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2)
    sessionStorage.setItem('device.id', id)
  }
  return id
}

function detectDeviceName() {
  const ua = navigator.userAgent
  const browser = ua.includes('Edg/') ? 'Edge'
    : ua.includes('OPR/') ? 'Opera'
    : ua.includes('Firefox/') ? 'Firefox'
    : ua.includes('Chrome/') ? 'Chrome'
    : ua.includes('Safari/') ? 'Safari'
    : 'Браузер'
  const os = ua.includes('Windows') ? 'Windows'
    : ua.includes('Mac') ? 'macOS'
    : ua.includes('Android') ? 'Android'
    : ua.includes('iPhone') || ua.includes('iPad') ? 'iOS'
    : ua.includes('Linux') ? 'Linux'
    : ''
  const mobile = /Android|iPhone|iPad/.test(ua)
  return { name: os ? `${browser} • ${os}` : browser, type: mobile ? 'phone' : 'computer' }
}

/**
 * Spotify Connect: every open tab is a "device" on the user's personal
 * Centrifugo channel. Devices announce themselves, the playing device
 * broadcasts its state, and any device can send commands (incl. transfer).
 */
export const useDeviceStore = defineStore('devices', () => {
  const player = usePlayerStore()
  const toasts = useToastStore()

  const myId = makeDeviceId()
  const my = { id: myId, ...detectDeviceName() }

  const devices = ref({}) // id -> { id, name, type, playing, lastSeen }
  const activeDeviceId = ref(null)
  const remoteState = ref(null) // last state broadcast from another device
  const connected = ref(false)

  let sub = null
  let helloTimer = null
  let stateTimer = null

  const isRemote = computed(
    () =>
      activeDeviceId.value &&
      activeDeviceId.value !== myId &&
      remoteState.value &&
      Date.now() - remoteState.value.receivedAt < DEVICE_TTL
  )

  const deviceList = computed(() => {
    const now = Date.now()
    const others = Object.values(devices.value).filter((d) => now - d.lastSeen < DEVICE_TTL)
    return [{ ...my, playing: player.isPlaying, self: true }, ...others]
  })

  const activeDevice = computed(
    () => deviceList.value.find((d) => d.id === activeDeviceId.value) || null
  )

  async function init() {
    if (sub) return
    try {
      const client = await getRealtime()
      sub = client.newSubscription(client.__playbackChannel)
      sub.on('publication', (ctx) => handleMessage(ctx.data))
      sub.on('subscribed', () => {
        connected.value = true
        publish({ t: 'ping', d: my })
        sayHello()
      })
      sub.subscribe()

      helloTimer = setInterval(sayHello, HELLO_INTERVAL)
      stateTimer = setInterval(() => {
        if (player.isPlaying && activeDeviceId.value === myId) broadcastState()
      }, STATE_INTERVAL)

      window.addEventListener('beforeunload', () => publish({ t: 'bye', d: my }))
      watchPlayer()
    } catch {
      /* realtime unavailable — Connect quietly off */
    }
  }

  function publish(data) {
    sub?.publish(data).catch(() => {})
  }

  function sayHello() {
    publish({ t: 'hello', d: my, playing: player.isPlaying })
  }

  function lightTrack(t) {
    if (!t) return null
    const cover = Array.isArray(t.cover)
      ? t.cover.find((c) => c.size >= 64)?.url || t.cover[0]?.url
      : t.cover?.[64] || t.cover?.[300] || null
    return {
      id: t.id,
      title: t.title,
      artists: (t.artists || []).map((a) => a.name).join(', '),
      cover,
      duration_ms: t.duration_ms,
    }
  }

  function broadcastState() {
    if (!player.currentTrack) return
    publish({
      t: 'state',
      d: my,
      playing: player.isPlaying,
      track: lightTrack(player.currentTrack),
      pos: Math.round(player.positionMs),
      dur: Math.round(player.durationMs),
      vol: player.volume,
      // Очередь для пультов: id контекста после курсора + ручная очередь.
      up: player.upcoming.slice(0, 60).map((t) => t.id),
      man: player.manualQueue.map((t) => ({ q: t.__qid, id: t.id })),
      ctx: player.contextName,
      ts: Date.now(),
    })
  }

  function handleMessage(msg) {
    // Our own publications echo back on the channel — ignore them.
    if (!msg || !msg.t || msg.d?.id === myId) return
    const dev = msg.d
    if (dev?.id) {
      devices.value = {
        ...devices.value,
        [dev.id]: { ...dev, playing: !!msg.playing, lastSeen: Date.now() },
      }
    }

    switch (msg.t) {
      case 'ping':
        sayHello()
        if (player.isPlaying && activeDeviceId.value === myId) broadcastState()
        break
      case 'bye': {
        const rest = { ...devices.value }
        delete rest[dev.id]
        devices.value = rest
        if (activeDeviceId.value === dev.id) {
          activeDeviceId.value = null
          remoteState.value = null
        }
        break
      }
      case 'state':
        remoteState.value = { ...msg, receivedAt: Date.now() }
        if (msg.playing) {
          activeDeviceId.value = dev.id
          // Single active device: someone else started playing — we stop.
          if (player.isPlaying) {
            player.togglePlay()
            toasts.show(`Играет на устройстве «${dev.name}»`)
          }
        }
        break
      case 'cmd':
        if (msg.to === myId) execCommand(msg)
        break
      case 'library-changed':
        // Лайк/подписка на другом устройстве — обновляем медиатеку здесь.
        import('@/stores/library').then(({ useLibraryStore }) =>
          useLibraryStore().load().catch(() => {})
        )
        break
    }
  }

  /** Сообщить остальным устройствам, что медиатека изменилась. */
  function notifyLibraryChanged() {
    publish({ t: 'library-changed', d: my })
  }

  async function execCommand(msg) {
    switch (msg.action) {
      case 'play':
        if (!player.isPlaying && player.currentTrack) player.togglePlay()
        break
      case 'pause':
        if (player.isPlaying) player.togglePlay()
        break
      case 'next':
        player.next()
        break
      case 'prev':
        player.prev()
        break
      case 'seek':
        player.seek(msg.value)
        broadcastState()
        break
      case 'volume':
        player.setVolume(msg.value)
        break
      case 'transfer':
        await receiveTransfer(msg.state)
        break
      case 'queue-add': {
        // Пульт добавил трек(и) в очередь этого устройства.
        const ids = Array.isArray(msg.value) ? msg.value : [msg.value]
        try {
          const { data } = await api.get('/tracks-bulk', { params: { ids: ids.join(',') } })
          data.data.forEach((t) => player.addToQueue(t))
          broadcastState()
        } catch {
          /* ignore */
        }
        break
      }
      case 'queue-remove-manual':
        player.removeFromManualQueue(msg.value)
        broadcastState()
        break
      case 'queue-remove-upcoming':
        player.removeUpcoming(msg.value)
        broadcastState()
        break
      case 'play-manual':
        await player.playManualItem(msg.value)
        broadcastState()
        break
      case 'play-upcoming':
        await player.playUpcomingItem(msg.value)
        broadcastState()
        break
      case 'push-transfer':
        // Another device asks us to hand playback over to `msg.target`.
        if (msg.target) {
          publish({ t: 'cmd', to: msg.target, action: 'transfer', state: snapshot() })
          if (player.isPlaying) player.togglePlay()
        }
        break
    }
  }

  /** This device was chosen in another tab's Connect list — take over. */
  async function receiveTransfer(state) {
    if (!state) return
    try {
      const ids = [...state.queueIds, ...state.manualIds]
      const { data } = await api.get('/tracks-bulk', { params: { ids: ids.join(',') } })
      const byId = new Map(data.data.map((t) => [t.id, t]))
      const tracks = state.queueIds.map((id) => byId.get(id)).filter(Boolean)
      const manual = state.manualIds.map((id) => byId.get(id)).filter(Boolean)
      await player.hydrate({
        tracks,
        index: state.index,
        manual,
        positionMs: state.pos,
        playing: state.playing,
        name: state.contextName,
      })
      activeDeviceId.value = myId
      remoteState.value = null
      broadcastState()
      if (state.playing && !player.isPlaying) {
        // The browser blocked autoplay without a gesture — be honest about it.
        toasts.show('Перенесено сюда — нажми ▶, чтобы продолжить')
      } else {
        toasts.show('Воспроизведение перенесено сюда')
      }
    } catch {
      toasts.show('Не удалось перенести воспроизведение')
    }
  }

  /** Click on a device in the Connect panel. */
  async function transferTo(deviceId) {
    if (deviceId === myId) {
      // Pull playback here: ask the playing device to hand over its snapshot.
      const from = activeDeviceId.value
      if (!isRemote.value || !from) return
      publish({ t: 'cmd', to: from, action: 'push-transfer', target: myId })
      return
    }
    // Push local playback to another device.
    if (!player.currentTrack) {
      toasts.show('Сначала включи что-нибудь здесь')
      return
    }
    publish({
      t: 'cmd',
      to: deviceId,
      action: 'transfer',
      state: snapshot(),
    })
    if (player.isPlaying) player.togglePlay()
    const name = devices.value[deviceId]?.name || 'устройство'
    toasts.show(`Играет на устройстве «${name}»`)
  }

  function snapshot() {
    return {
      queueIds: player.queue.map((t) => t.id),
      manualIds: player.manualQueue.map((t) => t.id),
      index: player.queueIndex,
      pos: Math.round(player.positionMs),
      playing: player.isPlaying,
      contextName: player.contextName,
    }
  }

  function sendCommand(action, value) {
    if (!activeDeviceId.value || activeDeviceId.value === myId) return
    publish({ t: 'cmd', to: activeDeviceId.value, action, value })
    // Optimistically nudge the remote state so the UI feels responsive.
    if (remoteState.value) {
      if (action === 'pause') remoteState.value.playing = false
      if (action === 'play') remoteState.value.playing = true
      if (action === 'seek') remoteState.value.pos = value
      if (action === 'volume') remoteState.value.vol = value
    }
  }

  function watchPlayer() {
    // Local playback started → this device becomes the active one.
    watch(
      () => player.isPlaying,
      (playing) => {
        if (playing) {
          activeDeviceId.value = myId
          remoteState.value = null
          broadcastState()
        } else if (activeDeviceId.value === myId) {
          broadcastState()
        }
      }
    )
    watch(
      () => player.currentTrack?.id,
      () => {
        if (activeDeviceId.value === myId || !activeDeviceId.value) {
          activeDeviceId.value = myId
          broadcastState()
        }
      }
    )
    // Изменения очереди тоже транслируем пультам.
    watch(
      () => [player.manualQueue.length, player.queue.length, player.queueIndex],
      () => {
        if (activeDeviceId.value === myId && player.currentTrack) broadcastState()
      }
    )
  }

  return {
    myId,
    myName: my.name,
    devices,
    deviceList,
    activeDeviceId,
    activeDevice,
    remoteState,
    isRemote,
    connected,
    init,
    transferTo,
    sendCommand,
    broadcastState,
    notifyLibraryChanged,
  }
})
