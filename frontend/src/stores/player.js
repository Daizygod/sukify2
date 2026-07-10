import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '@/lib/api'
import { buildCurve } from '@/lib/curves'

/**
 * The player owns the whole Web Audio graph:
 *
 *   audioEl[A] -> sourceA -> gainA \
 *                                    masterGain -> destination
 *   audioEl[B] -> sourceB -> gainB /
 *
 * Two decks let us crossfade A<->B. Per-track loudness normalization and the
 * crossfade envelope both live on the deck gains; master volume is separate.
 */
export const usePlayerStore = defineStore('player', () => {
  // --- reactive state ---
  const currentTrack = ref(null)
  const queue = ref([])
  const queueIndex = ref(-1)
  // Tracks explicitly queued by the user; they play before the context resumes.
  const manualQueue = ref([])
  const contextName = ref('')
  const isPlaying = ref(false)
  const positionMs = ref(0)
  const durationMs = ref(0)
  const volume = ref(0.8)
  const muted = ref(false)
  const repeat = ref('off') // off | all | one
  const shuffle = ref(false)

  // playback settings (loudness + crossfade), loaded from API for logged-in users
  const targetLufs = ref(-14)
  const defaultCrossfadeSeconds = ref(0)

  // --- non-reactive audio internals ---
  let ctx = null
  let master = null
  let decks = [] // [{ el, source, gain }]
  let active = 0
  let crossfading = false
  let rafId = null
  let onEndedHandler = null

  const inited = ref(false)

  function activeDeck() {
    return decks[active]
  }
  function idleDeck() {
    return decks[active ^ 1]
  }

  /** Lazily build the audio graph (must run after a user gesture). */
  function init() {
    if (inited.value) return
    ctx = new (window.AudioContext || window.webkitAudioContext)()
    master = ctx.createGain()
    master.gain.value = muted.value ? 0 : volume.value
    master.connect(ctx.destination)

    decks = [0, 1].map(() => {
      const el = new Audio()
      el.crossOrigin = 'anonymous'
      el.preload = 'auto'
      const source = ctx.createMediaElementSource(el)
      const gain = ctx.createGain()
      gain.gain.value = 0
      source.connect(gain)
      gain.connect(master)
      return { el, source, gain }
    })

    inited.value = true
  }

  /** gain multiplier that normalizes a track toward the target loudness. */
  function normGain(track) {
    if (!track || track.loudness_lufs == null) return 1
    const g = Math.pow(10, (targetLufs.value - track.loudness_lufs) / 20)
    // Clamp so a very quiet track can't blow past unity too far.
    return Math.min(Math.max(g, 0.05), 4)
  }

  function setMasterVolume() {
    if (!master) return
    master.gain.setTargetAtTime(muted.value ? 0 : volume.value, ctx.currentTime, 0.01)
  }

  // --- transport ---------------------------------------------------------

  async function playContext(tracks, startIndex = 0, meta = {}) {
    init()
    queue.value = tracks.filter((t) => t.stream_url)
    queueIndex.value = Math.min(startIndex, queue.value.length - 1)
    contextName.value = meta.name || ''
    originalOrder = null
    if (shuffle.value) reshuffleUpcoming()
    if (queueIndex.value < 0) return
    await loadAndPlay(queue.value[queueIndex.value])
  }

  async function playTrack(track, contextTracks = null, meta = {}) {
    if (contextTracks) {
      return playContext(contextTracks, contextTracks.findIndex((t) => t.id === track.id), meta)
    }
    return playContext([track], 0, meta)
  }

  async function loadAndPlay(track, deckIndex = active) {
    if (!track?.stream_url) return
    init()
    if (ctx.state === 'suspended') await ctx.resume()

    active = deckIndex
    const deck = decks[active]
    idleDeck().el.pause()

    currentTrack.value = track
    updateMediaSession(track)
    deck.el.src = track.stream_url
    deck.el.load()
    deck.gain.gain.cancelScheduledValues(ctx.currentTime)
    deck.gain.gain.value = normGain(track)

    try {
      await deck.el.play()
      isPlaying.value = true
      logPlay(track)
    } catch (e) {
      isPlaying.value = false
    }
    setMediaPlaybackState()
    bindEnded()
    startTicker()
  }

  function bindEnded() {
    decks.forEach((d) => {
      d.el.onended = null
    })
    activeDeck().el.onended = () => {
      if (!crossfading) next()
    }
  }

  function togglePlay() {
    if (!currentTrack.value) return
    const el = activeDeck().el
    if (el.paused) {
      ctx?.resume()
      el.play()
      isPlaying.value = true
    } else {
      el.pause()
      isPlaying.value = false
    }
    setMediaPlaybackState()
  }

  function seek(ms) {
    const el = activeDeck().el
    if (el && isFinite(ms)) {
      el.currentTime = ms / 1000
      positionMs.value = ms
    }
  }

  function setVolume(v) {
    volume.value = Math.min(Math.max(v, 0), 1)
    muted.value = false
    setMasterVolume()
  }

  function toggleMute() {
    muted.value = !muted.value
    setMasterVolume()
  }

  /** The track that will play after the current one, honoring the manual queue. */
  function peekNext() {
    if (manualQueue.value.length) return manualQueue.value[0]
    if (queueIndex.value < queue.value.length - 1) return queue.value[queueIndex.value + 1]
    if (repeat.value === 'all' && queue.value.length) return queue.value[0]
    return null
  }

  /** Move the cursor onto `t` (must be the peekNext() result). */
  function consumeNext(t) {
    if (manualQueue.value[0] && manualQueue.value[0].__qid === t.__qid) {
      manualQueue.value.shift()
    } else if (queueIndex.value < queue.value.length - 1) {
      queueIndex.value++
    } else if (repeat.value === 'all') {
      queueIndex.value = 0
    }
  }

  async function next() {
    if (repeat.value === 'one' && currentTrack.value) {
      return loadAndPlay(currentTrack.value)
    }
    const t = peekNext()
    if (!t) {
      stop()
      return
    }
    consumeNext(t)
    crossfading = false
    await loadAndPlay(t)
  }

  async function prev() {
    // Restart current track if we're more than 3s in.
    if (positionMs.value > 3000) return seek(0)
    if (queueIndex.value > 0) {
      queueIndex.value--
      await loadAndPlay(queue.value[queueIndex.value])
    } else {
      seek(0)
    }
  }

  function stop() {
    decks.forEach((d) => d.el?.pause())
    isPlaying.value = false
    positionMs.value = 0
  }

  // --- queue management ---------------------------------------------------

  let qidCounter = 0

  /** «Добавить в очередь» — plays after the current track, before the context. */
  function addToQueue(track) {
    if (!track?.stream_url) return false
    manualQueue.value.push({ ...track, __qid: ++qidCounter })
    return true
  }

  function removeFromManualQueue(qid) {
    manualQueue.value = manualQueue.value.filter((t) => t.__qid !== qid)
  }

  /** Remove the i-th upcoming context track (0 = right after current). */
  function removeUpcoming(i) {
    queue.value.splice(queueIndex.value + 1 + i, 1)
  }

  function clearManualQueue() {
    manualQueue.value = []
  }

  async function playManualItem(qid) {
    const idx = manualQueue.value.findIndex((t) => t.__qid === qid)
    if (idx === -1) return
    const [t] = manualQueue.value.splice(idx, 1)
    crossfading = false
    await loadAndPlay(t)
  }

  /** Jump to the i-th upcoming context track. */
  async function playUpcomingItem(i) {
    const abs = queueIndex.value + 1 + i
    if (!queue.value[abs]) return
    queueIndex.value = abs
    crossfading = false
    await loadAndPlay(queue.value[abs])
  }

  function setManualQueue(arr) {
    manualQueue.value = arr
  }

  function setUpcoming(arr) {
    queue.value = [...queue.value.slice(0, queueIndex.value + 1), ...arr]
  }

  // --- shuffle -------------------------------------------------------------

  let originalOrder = null

  function reshuffleUpcoming() {
    if (originalOrder === null) originalOrder = [...queue.value]
    const head = queue.value.slice(0, queueIndex.value + 1)
    const rest = queue.value.slice(queueIndex.value + 1)
    for (let i = rest.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1))
      ;[rest[i], rest[j]] = [rest[j], rest[i]]
    }
    queue.value = [...head, ...rest]
  }

  function setShuffle(on) {
    shuffle.value = on
    if (!queue.value.length) return
    if (on) {
      reshuffleUpcoming()
    } else if (originalOrder) {
      const cur = currentTrack.value
      queue.value = originalOrder
      originalOrder = null
      const idx = queue.value.findIndex((t) => t.id === cur?.id)
      if (idx >= 0) queueIndex.value = idx
    }
  }

  // --- crossfade ---------------------------------------------------------

  async function maybeCrossfade() {
    if (crossfading) return
    if (repeat.value === 'one') return
    const nextTrack = peekNext()
    if (!nextTrack) return

    const el = activeDeck().el
    const remainingMs = (el.duration - el.currentTime) * 1000
    if (!isFinite(remainingMs)) return

    // Resolve the fade envelope for this pair (community default or global).
    const transition = await resolveTransition(currentTrack.value, nextTrack)
    const fadeOutLen = transition
      ? transition.fade_out_end_ms - transition.fade_out_start_ms
      : defaultCrossfadeSeconds.value * 1000
    if (fadeOutLen <= 0) return

    if (remainingMs <= fadeOutLen) {
      // Consume the cursor up-front so the queue panel reflects the switch.
      consumeNext(nextTrack)
      startCrossfade(nextTrack, transition, fadeOutLen)
    }
  }

  const transitionCache = new Map()
  async function resolveTransition(from, to) {
    if (!from || !to) return null
    const key = `${from.id}:${to.id}`
    if (transitionCache.has(key)) return transitionCache.get(key)
    let t = null
    try {
      const { data } = await api.get('/transitions', { params: { from: from.id, to: to.id } })
      t = data.data || null
    } catch {
      t = null
    }
    transitionCache.set(key, t)
    return t
  }

  async function startCrossfade(nextTrack, transition, fadeOutLen) {
    crossfading = true
    const curOut = activeDeck()
    const incoming = idleDeck()
    const incomingIndex = active ^ 1

    const fadeInLen = transition
      ? transition.fade_in_full_volume_ms - transition.fade_in_start_ms
      : fadeOutLen
    const curve = transition?.curve_type || 'linear'

    // Prime the incoming deck.
    incoming.el.src = nextTrack.stream_url
    incoming.el.load()
    incoming.gain.gain.cancelScheduledValues(ctx.currentTime)
    incoming.gain.gain.value = 0
    try {
      await incoming.el.play()
    } catch {
      crossfading = false
      return
    }

    const now = ctx.currentTime
    const targetIn = normGain(nextTrack)

    // Fade current out, next in — with the selected curve shape.
    applyCurve(curOut.gain, curOut.gain.value, 0, now, fadeOutLen / 1000, curve, true)
    applyCurve(incoming.gain, 0, targetIn, now, fadeInLen / 1000, curve, false)

    // Hand over once the outgoing fade completes (cursor already advanced).
    setTimeout(() => {
      curOut.el.pause()
      active = incomingIndex
      currentTrack.value = nextTrack
      updateMediaSession(nextTrack)
      crossfading = false
      logPlay(nextTrack)
      bindEnded()
    }, fadeOutLen)
  }

  function applyCurve(gainNode, from, to, startTime, durationSec, curveType, descending) {
    const values = buildCurve(from, to, curveType, 64)
    try {
      gainNode.gain.cancelScheduledValues(startTime)
      gainNode.gain.setValueAtTime(from, startTime)
      gainNode.gain.setValueCurveAtTime(Float32Array.from(values), startTime, Math.max(durationSec, 0.05))
    } catch {
      gainNode.gain.linearRampToValueAtTime(to, startTime + durationSec)
    }
  }

  // --- ticker ------------------------------------------------------------

  function startTicker() {
    stopTicker()
    const tick = () => {
      const el = activeDeck()?.el
      if (el) {
        positionMs.value = el.currentTime * 1000
        durationMs.value = (el.duration || 0) * 1000
        isPlaying.value = !el.paused
        maybeCrossfade()
      }
      rafId = requestAnimationFrame(tick)
    }
    rafId = requestAnimationFrame(tick)
  }
  function stopTicker() {
    if (rafId) cancelAnimationFrame(rafId)
    rafId = null
  }

  // --- side effects ------------------------------------------------------

  function logPlay(track) {
    api.post(`/tracks/${track.id}/play`).catch(() => {})
  }

  // --- OS media integration (lock screen / notification tray / headset keys) --
  function updateMediaSession(track) {
    if (!('mediaSession' in navigator) || !track) return

    const covers = Array.isArray(track.cover)
      ? track.cover
      : track.cover
        ? Object.entries(track.cover).map(([size, url]) => ({ size: Number(size), url }))
        : []
    const artwork = covers
      .filter((c) => c.url)
      .map((c) => ({ src: c.url, sizes: `${c.size}x${c.size}`, type: 'image/webp' }))

    try {
      navigator.mediaSession.metadata = new window.MediaMetadata({
        title: track.title || '',
        artist: (track.artists || []).map((a) => a.name).join(', '),
        album: track.release?.title || '',
        artwork,
      })
      navigator.mediaSession.setActionHandler('play', () => togglePlay())
      navigator.mediaSession.setActionHandler('pause', () => togglePlay())
      navigator.mediaSession.setActionHandler('previoustrack', () => prev())
      navigator.mediaSession.setActionHandler('nexttrack', () => next())
      navigator.mediaSession.setActionHandler('seekto', (d) => {
        if (d.seekTime != null) seek(d.seekTime * 1000)
      })
    } catch {
      /* MediaSession unsupported/partial */
    }
  }

  function setMediaPlaybackState() {
    if ('mediaSession' in navigator) {
      navigator.mediaSession.playbackState = isPlaying.value ? 'playing' : 'paused'
    }
  }

  async function loadSettings() {
    try {
      const { data } = await api.get('/playback-settings')
      targetLufs.value = data.data.target_loudness_lufs
      defaultCrossfadeSeconds.value = data.data.default_crossfade_seconds
    } catch {
      /* guest: keep defaults */
    }
  }

  const progress = computed(() =>
    durationMs.value ? Math.min(positionMs.value / durationMs.value, 1) : 0
  )

  /** Upcoming tracks from the playing context (after the cursor). */
  const upcoming = computed(() => queue.value.slice(queueIndex.value + 1))

  return {
    // state
    currentTrack, queue, queueIndex, manualQueue, contextName, isPlaying,
    positionMs, durationMs,
    volume, muted, repeat, shuffle, targetLufs, defaultCrossfadeSeconds, inited,
    // getters
    progress, upcoming,
    // actions
    init, playContext, playTrack, togglePlay, seek, setVolume, toggleMute,
    next, prev, stop, loadSettings, setShuffle,
    addToQueue, removeFromManualQueue, removeUpcoming, clearManualQueue,
    playManualItem, playUpcomingItem, setManualQueue, setUpcoming,
  }
})
