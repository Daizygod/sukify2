/**
 * Звуковая часть микса.
 *
 * К двум декам плеера добавляется по цепочке обработки:
 *
 *   source → filter → low → mid → high → gain → master
 *
 * filter — НЧ/ВЧ-срез, три полки — эквалайзер, gain — громкость. Во время
 * перехода все четыре параметра ведутся по кривым из shapes.js, а после —
 * возвращаются в нейтраль, чтобы следующий трек играл чистым.
 */

import { EQ_SHAPES, FILTER_SHAPES, VOLUME_SHAPES, sample } from './shapes.js'

const OPEN_LP = 20000
const OPEN_HP = 20
const CURVE_STEPS = 128
// Ниже этого setValueCurveAtTime уже мусорит — короткие переходы доводим рампой.
const MIN_CURVE_SEC = 0.05

/** Достраивает деку обработку. Возвращает узел, к которому цеплять source. */
export function buildChain(ctx, gainNode) {
  const filter = ctx.createBiquadFilter()
  filter.type = 'lowpass'
  filter.frequency.value = OPEN_LP
  filter.Q.value = 0.7

  const low = ctx.createBiquadFilter()
  low.type = 'lowshelf'
  low.frequency.value = 200
  low.gain.value = 0

  const mid = ctx.createBiquadFilter()
  mid.type = 'peaking'
  mid.frequency.value = 1000
  mid.Q.value = 0.8
  mid.gain.value = 0

  const high = ctx.createBiquadFilter()
  high.type = 'highshelf'
  high.frequency.value = 4000
  high.gain.value = 0

  filter.connect(low)
  low.connect(mid)
  mid.connect(high)
  high.connect(gainNode)

  return { input: filter, filter, low, mid, high }
}

/** Возвращает деку в нейтральное звучание. */
export function resetChain(chain, ctx) {
  if (!chain) return
  const t = ctx.currentTime
  for (const p of [chain.filter.frequency, chain.low.gain, chain.mid.gain, chain.high.gain]) {
    p.cancelScheduledValues(t)
  }
  chain.filter.type = 'lowpass'
  chain.filter.frequency.setValueAtTime(OPEN_LP, t)
  chain.low.gain.setValueAtTime(0, t)
  chain.mid.gain.setValueAtTime(0, t)
  chain.high.gain.setValueAtTime(0, t)
}

/** Ведёт AudioParam по кривой; на совсем коротких отрезках — линейной рампой. */
function ride(param, points, startTime, durationSec, scale = 1) {
  if (!points || !points.length) return
  const values = sample(points, CURVE_STEPS)
  const curve = new Float32Array(values.length)
  for (let i = 0; i < values.length; i++) {
    const v = values[i] * scale
    curve[i] = Number.isFinite(v) ? v : 0
  }
  try {
    param.cancelScheduledValues(startTime)
    param.setValueAtTime(curve[0], startTime)
    param.setValueCurveAtTime(curve, startTime, Math.max(durationSec, MIN_CURVE_SEC))
  } catch {
    // Некоторые браузеры ругаются на перекрытие расписаний — доводим рампой.
    param.cancelScheduledValues(startTime)
    param.setValueAtTime(param.value, startTime)
    param.linearRampToValueAtTime(curve[curve.length - 1], startTime + Math.max(durationSec, MIN_CURVE_SEC))
  }
}

/** Белый шум для формы «Шум + затухание в конце». */
function makeNoise(ctx, destination, points, startTime, durationSec) {
  const frames = Math.ceil(ctx.sampleRate * Math.max(durationSec, MIN_CURVE_SEC))
  const buffer = ctx.createBuffer(1, frames, ctx.sampleRate)
  const data = buffer.getChannelData(0)
  for (let i = 0; i < frames; i++) data[i] = Math.random() * 2 - 1

  const src = ctx.createBufferSource()
  src.buffer = buffer
  const gain = ctx.createGain()
  gain.gain.value = 0
  // Шум режем сверху, иначе он звенит поверх музыки.
  const tone = ctx.createBiquadFilter()
  tone.type = 'lowpass'
  tone.frequency.value = 6000

  src.connect(tone)
  tone.connect(gain)
  gain.connect(destination)

  ride(gain.gain, points, startTime, durationSec)
  src.start(startTime)
  src.stop(startTime + durationSec + 0.1)
  src.onended = () => {
    try {
      gain.disconnect()
      tone.disconnect()
    } catch {
      /* уже отцеплено */
    }
  }
}

/**
 * Расписывает переход по обеим декам.
 *
 * @param {object}   o
 * @param {AudioContext} o.ctx
 * @param {number}   o.startTime      время начала в шкале ctx
 * @param {number}   o.durationSec    длина перекрытия
 * @param {object}   o.shapes         { volume, eq, filter } — идентификаторы форм
 * @param {object}   o.out            { gain, chain, level } уходящий дек
 * @param {object}   o.in             { gain, chain, level } приходящий дек
 * @param {AudioNode} o.noiseTarget   куда подмешивать шум (обычно master)
 */
export function scheduleTransition({ ctx, startTime, durationSec, shapes, out, in: inc, noiseTarget }) {
  const volume = VOLUME_SHAPES[shapes.volume] || VOLUME_SHAPES.crossfade
  const eq = EQ_SHAPES[shapes.eq] || EQ_SHAPES.none
  const filter = FILTER_SHAPES[shapes.filter] || FILTER_SHAPES.none

  // Громкость: форма нормирована 0..1, множим на выравнивание по громкости трека.
  ride(out.gain.gain, volume.out, startTime, durationSec, out.level ?? 1)
  ride(inc.gain.gain, volume.in, startTime, durationSec, inc.level ?? 1)

  // Эквалайзер: полосы, которых нет в форме, не трогаем вовсе.
  for (const [side, chain] of [
    ['out', out.chain],
    ['in', inc.chain],
  ]) {
    const bands = eq[side] || {}
    if (!chain) continue
    if (bands.low) ride(chain.low.gain, bands.low, startTime, durationSec)
    if (bands.mid) ride(chain.mid.gain, bands.mid, startTime, durationSec)
    if (bands.high) ride(chain.high.gain, bands.high, startTime, durationSec)
  }

  // Фильтр: тип ставим до расписания, иначе первая точка попадёт в старый тип.
  for (const [side, chain] of [
    ['out', out.chain],
    ['in', inc.chain],
  ]) {
    const spec = filter[side]
    if (!chain) continue
    if (!spec) {
      chain.filter.type = 'lowpass'
      chain.filter.frequency.cancelScheduledValues(startTime)
      chain.filter.frequency.setValueAtTime(OPEN_LP, startTime)
      continue
    }
    chain.filter.type = spec.type
    ride(chain.filter.frequency, spec.points, startTime, durationSec)
  }

  if (filter.noise && noiseTarget) {
    makeNoise(ctx, noiseTarget, filter.noise, startTime, durationSec)
  }
}

/** Открытые значения фильтра — пригодятся снаружи (тесты, сброс). */
export const NEUTRAL = { lowpass: OPEN_LP, highpass: OPEN_HP }
