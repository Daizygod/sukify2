import { ref, computed, onMounted, onUnmounted } from 'vue'
import { usePlayerStore } from '@/stores/player'
import { useDeviceStore } from '@/stores/devices'

/**
 * Единая точка «что сейчас играет и как этим управлять» с учётом Connect:
 * если играет другое устройство — показываем его состояние и шлём команды.
 * Используется мобильным мини-плеером и экраном «Сейчас играет».
 */
export function usePlaybackControls() {
  const player = usePlayerStore()
  const devices = useDeviceStore()

  const remote = computed(() => devices.isRemote)
  const rs = computed(() => devices.remoteState)

  // Интерполяция позиции между broadcast'ами удалённого устройства.
  const nowTick = ref(Date.now())
  let tickTimer = null
  onMounted(() => {
    tickTimer = setInterval(() => (nowTick.value = Date.now()), 1000)
  })
  onUnmounted(() => clearInterval(tickTimer))

  /** Локальный полный трек (null, когда играет другое устройство). */
  const localTrack = computed(() => player.currentTrack)

  /** Что показывать: {title, artists (строка), coverUrl, cover (массив|null)}. */
  const view = computed(() => {
    if (remote.value && rs.value?.track) {
      const t = rs.value.track
      return { title: t.title, artists: t.artists || '', coverUrl: t.cover || null, cover: null }
    }
    const t = player.currentTrack
    if (!t) return null
    return {
      title: t.title,
      artists: (t.artists || []).map((a) => a.name).join(', '),
      coverUrl: null,
      cover: t.cover,
    }
  })

  const hasPlayback = computed(() => !!view.value)

  const shownPlaying = computed(() => (remote.value ? !!rs.value?.playing : player.isPlaying))
  const shownDuration = computed(() => (remote.value ? rs.value?.dur || 0 : player.durationMs))
  const shownPosition = computed(() => {
    if (!remote.value) return player.positionMs
    const s = rs.value
    if (!s) return 0
    const drift = s.playing ? nowTick.value - s.receivedAt : 0
    return Math.min(s.pos + drift, s.dur || Infinity)
  })
  const shownProgress = computed(() =>
    shownDuration.value ? Math.min(shownPosition.value / shownDuration.value, 1) : 0
  )

  function togglePlay() {
    if (remote.value) return devices.sendCommand(shownPlaying.value ? 'pause' : 'play')
    player.togglePlay()
  }
  function next() {
    remote.value ? devices.sendCommand('next') : player.next()
  }
  function prev() {
    remote.value ? devices.sendCommand('prev') : player.prev()
  }
  function seek(frac) {
    const ms = frac * shownDuration.value
    remote.value ? devices.sendCommand('seek', ms) : player.seek(ms)
  }

  return {
    player,
    devices,
    remote,
    localTrack,
    view,
    hasPlayback,
    shownPlaying,
    shownDuration,
    shownPosition,
    shownProgress,
    togglePlay,
    next,
    prev,
    seek,
  }
}
