import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '@/lib/api'
import { buildCurve } from '@/lib/curves'
// Циклический импорт (devices ↔ player) безопасен: стор берём лениво в экшенах.
import { useDeviceStore } from '@/stores/devices'

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
  let fadeTimer = null // глушит уходящий дек в конце кроссфейда
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
    // Отладочный доступ (и для E2E-тестов): аудиоэлементы и гейны деков.
    window.__sukifyDecks = decks.map((d) => d.el)
    window.__sukifyGains = decks.map((d) => d.gain)
    window.__sukifyNorm = () => ({ target: targetLufs.value, track: currentTrack.value?.loudness_lufs, active })

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
    // Перцептивная шкала (кубическая ≈ логарифм слуха): положение ползунка →
    // усиление. Внизу шкалы шаг громкости мелкий, настраивать тихо — удобно.
    const gain = muted.value ? 0 : Math.pow(volume.value, 3)
    master.gain.setTargetAtTime(gain, ctx.currentTime, 0.01)
  }

  // --- transport ---------------------------------------------------------

  /**
   * Пока активно другое устройство, все действия — это команды ему
   * (как в Spotify Connect): вернёт devices-store или null.
   */
  function remoteTarget() {
    try {
      const d = useDeviceStore()
      return d.isRemote ? d : null
    } catch {
      return null
    }
  }

  async function playContext(tracks, startIndex = 0, meta = {}) {
    const d = remoteTarget()
    if (d) {
      d.sendCommand('play-context', {
        ids: tracks.filter((t) => t.stream_url).map((t) => t.id),
        index: startIndex,
        name: meta.name || '',
      })
      return
    }
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
    // Локальный запуск отменяет возможное затухание передачи.
    clearTimeout(fadeOutTimer)
    setMasterVolume()

    currentTrack.value = track
    // Таймлайн сразу с нуля, не дожидаясь timeupdate нового аудио.
    positionMs.value = 0
    durationMs.value = track.duration_ms || 0
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
    const d = remoteTarget()
    if (d) {
      d.sendCommand(d.remoteState?.playing ? 'pause' : 'play')
      return
    }
    if (!currentTrack.value) return
    const el = activeDeck().el
    if (el.paused) {
      ctx?.resume()
      // Если шло затухание при передаче — отменяем его и возвращаем громкость.
      clearTimeout(fadeOutTimer)
      setMasterVolume()
      el.play().catch(() => {
        isPlaying.value = false
        setMediaPlaybackState()
      })
      isPlaying.value = true
    } else {
      el.pause()
      // Пауза во время кроссфейда: старый дек ещё дозатухает по таймеру и без
      // этого доиграл бы до конца. Обрываем фейд и глушим его сразу.
      if (crossfading) cancelCrossfade()
      isPlaying.value = false
    }
    setMediaPlaybackState()
  }

  /**
   * Пауза строго локального звука — без маршрутизации на пульт.
   * Нужна devices-стору: «другое устройство заиграло — глушим себя».
   */
  function pauseLocal() {
    if (!inited.value) return
    const el = activeDeck().el
    if (!el.paused) el.pause()
    if (crossfading) cancelCrossfade()
    isPlaying.value = false
    setMediaPlaybackState()
  }

  /**
   * Передача воспроизведения: старое устройство не обрубает звук, а плавно
   * затухает ~секунду, пока новое уже играет — без ощущения «вырубилось».
   */
  let fadeOutTimer = null
  function fadeOutAndPause(ms = 1100) {
    if (!inited.value || !isPlaying.value) return pauseLocal()
    clearTimeout(fadeOutTimer)
    const t = ctx.currentTime
    master.gain.cancelScheduledValues(t)
    master.gain.setValueAtTime(master.gain.value, t)
    master.gain.linearRampToValueAtTime(0.0001, t + ms / 1000)
    fadeOutTimer = setTimeout(() => {
      pauseLocal()
      setMasterVolume() // вернуть громкость для будущего локального воспроизведения
    }, ms)
  }

  /** Мгновенно завершает кроссфейд: уходящий дек — стоп, активный — на номинал. */
  function cancelCrossfade() {
    clearTimeout(fadeTimer)
    crossfading = false
    const out = idleDeck()
    out.el.pause()
    out.gain.gain.cancelScheduledValues(ctx.currentTime)
    out.gain.gain.value = 0
    const cur = activeDeck()
    cur.gain.gain.cancelScheduledValues(ctx.currentTime)
    cur.gain.gain.value = normGain(currentTrack.value)
  }

  function seek(ms) {
    const d = remoteTarget()
    if (d) {
      d.sendCommand('seek', ms)
      return
    }
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
    const d = remoteTarget()
    if (d) return d.sendCommand('next')
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
    const d = remoteTarget()
    if (d) return d.sendCommand('prev')
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
    const d = remoteTarget()
    if (d) {
      // Очередью владеет активное устройство — шлём ему.
      d.sendCommand('queue-add', [track.id])
      return true
    }
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

  /** Restore a full playback snapshot (Connect transfer onto this device). */
  async function hydrate({ tracks, index = 0, manual = [], positionMs = 0, playing = true, name = '' }) {
    init()
    queue.value = tracks.filter((t) => t.stream_url)
    queueIndex.value = Math.min(index, queue.value.length - 1)
    manualQueue.value = manual.map((t) => ({ ...t, __qid: ++qidCounter }))
    contextName.value = name
    originalOrder = null
    const t = queue.value[queueIndex.value]
    if (!t) return
    await loadAndPlay(t)
    if (positionMs > 0) seek(positionMs)
    if (!playing) {
      activeDeck().el.pause()
      isPlaying.value = false
      setMediaPlaybackState()
    }
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
    const d = remoteTarget()
    if (d) return d.sendCommand('shuffle', !!on)
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

  /** Режим повтора; на пульте — команда активному устройству. */
  function setRepeat(mode) {
    const d = remoteTarget()
    if (d) return d.sendCommand('repeat', mode)
    repeat.value = mode
  }

  function cycleRepeat() {
    const d = remoteTarget()
    const cur = d ? d.remoteState?.repeat || 'off' : repeat.value
    setRepeat(cur === 'off' ? 'all' : cur === 'all' ? 'one' : 'off')
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

  /** Drop cached pair transitions (after creating/voting in the editor). */
  function invalidateTransitions() {
    transitionCache.clear()
  }

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
    // Глобальный кроссфейд (без своего перехода) — равная мощность, как в Spotify.
    const curve = transition?.curve_type || 'equal_power'

    // Prime the incoming deck (переход может задавать точку входа в трек Б).
    incoming.el.src = nextTrack.stream_url
    incoming.el.load()
    incoming.gain.gain.cancelScheduledValues(ctx.currentTime)
    incoming.gain.gain.value = 0
    if (transition?.fade_in_start_ms > 0) {
      incoming.el.currentTime = transition.fade_in_start_ms / 1000
    }
    try {
      await incoming.el.play()
    } catch {
      crossfading = false
      return
    }

    const now = ctx.currentTime
    const targetIn = normGain(nextTrack)
    const fromOut = curOut.gain.gain.value

    // Fade current out, next in — with the selected curve shape.
    applyCurve(curOut.gain, fromOut, 0, now, fadeOutLen / 1000, curve)
    applyCurve(incoming.gain, 0, targetIn, now, fadeInLen / 1000, curve)

    debugCrossfade(curOut, incoming, fadeOutLen, curve, fromOut, targetIn)

    // UI переключается сразу: играет уже новый трек (старый дозатухает фоном).
    active = incomingIndex
    currentTrack.value = nextTrack
    updateMediaSession(nextTrack)
    logPlay(nextTrack)
    bindEnded()

    // Старый дек глушим, когда его затухание закончилось.
    clearTimeout(fadeTimer)
    fadeTimer = setTimeout(() => {
      curOut.el.pause()
      crossfading = false
    }, fadeOutLen)
  }

  function applyCurve(gainNode, from, to, startTime, durationSec, curveType) {
    if (!Number.isFinite(from)) from = gainNode.gain.value
    if (!Number.isFinite(from)) from = 0
    if (!Number.isFinite(to)) to = 0
    const values = buildCurve(from, to, curveType, 64).map((v) => (Number.isFinite(v) ? v : to))
    try {
      gainNode.gain.cancelScheduledValues(startTime)
      gainNode.gain.setValueAtTime(from, startTime)
      gainNode.gain.setValueCurveAtTime(Float32Array.from(values), startTime, Math.max(durationSec, 0.05))
    } catch (e) {
      console.warn('[xfade] setValueCurveAtTime failed, linear fallback:', e?.message)
      // Якорим текущее значение, иначе linearRamp без предыдущего события
      // прыгает к цели мгновенно (это и слышно как «провал» громкости).
      gainNode.gain.setValueAtTime(from, startTime)
      gainNode.gain.linearRampToValueAtTime(to, startTime + durationSec)
    }
  }

  /**
   * Отладка кроссфейда: раз в секунду пишем в консоль фактические gain'ы
   * обоих деков и мастера, в конце — сводная console.table.
   */
  function debugCrossfade(outDeck, inDeck, fadeMs, curve, fromOut, targetIn) {
    const t0 = ctx.currentTime
    const rows = []
    console.log(
      `[xfade] start: curve=${curve} len=${(fadeMs / 1000).toFixed(1)}s ` +
      `out ${fromOut.toFixed(3)}→0, in 0→${targetIn.toFixed(3)}, ` +
      `master=${master.gain.value.toFixed(3)} (volume=${Math.round(volume.value * 100)}%)`
    )
    const timer = setInterval(() => {
      const t = ctx.currentTime - t0
      const outG = outDeck.gain.gain.value
      const inG = inDeck.gain.gain.value
      const m = master.gain.value
      rows.push({
        't, сек': +t.toFixed(1),
        'out gain': +outG.toFixed(3),
        'in gain': +inG.toFixed(3),
        'master': +m.toFixed(3),
        'out×master': +(outG * m).toFixed(3),
        'in×master': +(inG * m).toFixed(3),
        'мощность Σ': +Math.sqrt(outG * outG + inG * inG).toFixed(3),
      })
      console.log(`[xfade] t=${t.toFixed(1)}s out=${outG.toFixed(3)} in=${inG.toFixed(3)} master=${m.toFixed(3)}`)
      if (t >= fadeMs / 1000) {
        clearInterval(timer)
        console.table(rows)
      }
    }, 1000)
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
    init, playContext, playTrack, togglePlay, pauseLocal, fadeOutAndPause, seek, setVolume, toggleMute,
    next, prev, stop, loadSettings, setShuffle, setRepeat, cycleRepeat, hydrate, invalidateTransitions,
    addToQueue, removeFromManualQueue, removeUpcoming, clearManualQueue,
    playManualItem, playUpcomingItem, setManualQueue, setUpcoming,
  }
})
