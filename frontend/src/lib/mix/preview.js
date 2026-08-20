/**
 * Предпрослушка перехода: играет только сам стык — пару секунд разгона,
 * перекрытие и немного нового трека. Живёт в своём AudioContext, чтобы не
 * трогать основной плеер (его на время предпрослушки ставим на паузу).
 */

import { buildChain, scheduleTransition } from './engine'

const LEAD_MS = 2000 // сколько играем до перекрытия
const TAIL_MS = 2500 // и сколько после

export function createPreview() {
  let ctx = null
  let master = null
  let decks = null
  let timers = []
  let raf = null
  let onTick = null
  let onEnd = null
  let startedAt = 0
  let plan = null

  function ensure() {
    if (ctx) return
    ctx = new (window.AudioContext || window.webkitAudioContext)()
    master = ctx.createGain()
    master.gain.value = 1
    master.connect(ctx.destination)
    decks = [0, 1].map(() => {
      const el = new Audio()
      el.crossOrigin = 'anonymous'
      el.preload = 'auto'
      const source = ctx.createMediaElementSource(el)
      const gain = ctx.createGain()
      gain.gain.value = 0
      const chain = buildChain(ctx, gain)
      source.connect(chain.input)
      gain.connect(master)
      return { el, gain, chain }
    })
  }

  function clearTimers() {
    timers.forEach(clearTimeout)
    timers = []
    if (raf) cancelAnimationFrame(raf)
    raf = null
  }

  function stop() {
    clearTimers()
    if (!decks) return
    for (const d of decks) {
      try {
        d.el.pause()
      } catch {
        /* уже остановлен */
      }
      d.gain.gain.cancelScheduledValues(ctx.currentTime)
      d.gain.gain.value = 0
    }
    plan = null
    onTick?.(-1)
  }

  /**
   * @param {object} o
   * @param {object} o.fromTrack  { stream_url }
   * @param {object} o.toTrack    { stream_url }
   * @param {number} o.outStartMs начало перекрытия на уходящем треке
   * @param {number} o.inStartMs  начало перекрытия на приходящем
   * @param {number} o.lengthMs   длина перекрытия
   * @param {object} o.shapes     { volume, eq, filter }
   */
  async function play(o) {
    ensure()
    stop()
    if (ctx.state === 'suspended') await ctx.resume()

    const lead = Math.min(LEAD_MS, o.outStartMs)
    const [a, b] = decks

    a.el.src = o.fromTrack.stream_url
    b.el.src = o.toTrack.stream_url
    a.el.currentTime = Math.max(0, (o.outStartMs - lead) / 1000)
    b.el.currentTime = Math.max(0, o.inStartMs / 1000)

    a.gain.gain.cancelScheduledValues(ctx.currentTime)
    a.gain.gain.value = 1
    b.gain.gain.cancelScheduledValues(ctx.currentTime)
    b.gain.gain.value = 0

    try {
      await a.el.play()
    } catch {
      return
    }

    plan = { lead, length: o.lengthMs }
    startedAt = performance.now()

    timers.push(
      setTimeout(async () => {
        try {
          await b.el.play()
        } catch {
          /* приходящий не завёлся — переход всё равно отрисуем */
        }
        scheduleTransition({
          ctx,
          startTime: ctx.currentTime,
          durationSec: o.lengthMs / 1000,
          shapes: o.shapes,
          out: { gain: a.gain, chain: a.chain, level: 1 },
          in: { gain: b.gain, chain: b.chain, level: 1 },
          noiseTarget: master,
        })
      }, lead)
    )

    timers.push(
      setTimeout(() => {
        stop()
        onEnd?.()
      }, lead + o.lengthMs + TAIL_MS)
    )

    const tick = () => {
      if (!plan) return
      const elapsed = performance.now() - startedAt - plan.lead
      onTick?.(elapsed < 0 ? 0 : Math.min(1, elapsed / plan.length))
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
  }

  function destroy() {
    stop()
    try {
      ctx?.close()
    } catch {
      /* уже закрыт */
    }
    ctx = null
    decks = null
  }

  return {
    play,
    stop,
    destroy,
    set onProgress(fn) {
      onTick = fn
    },
    set onFinished(fn) {
      onEnd = fn
    },
  }
}
