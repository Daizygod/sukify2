// Эталон для тестов Android: значения кривых, пресеты и совместимость темпов
// из веба (frontend/src/lib/mix/*.js). Запуск из корня репозитория:
//   node android/tools/mix_reference.mjs > android/app/src/test/resources/mix_reference.json
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'

const lib = (f) => import(pathToFileURL(resolve('frontend/src/lib/mix', f)).href)
const { VOLUME_SHAPES, EQ_SHAPES, FILTER_SHAPES, PRESETS, PRESET_ORDER, resolveShapes, beatsMatch, valueAt } = await lib('shapes.js')
const camelot = await lib('camelot.js')

const ts = Array.from({ length: 21 }, (_, i) => i / 20)
const sample = (points) => (points ? ts.map((t) => +valueAt(points, t).toFixed(6)) : null)

const out = { ts, volume: {}, eq: {}, filter: {}, presets: {}, beats: [], camelot: {} }
for (const [id, s] of Object.entries(VOLUME_SHAPES)) out.volume[id] = { out: sample(s.out), in: sample(s.in) }
for (const [id, s] of Object.entries(EQ_SHAPES)) {
  out.eq[id] = {}
  for (const side of ['out', 'in']) {
    out.eq[id][side] = {}
    for (const band of ['low', 'mid', 'high']) out.eq[id][side][band] = sample(s[side]?.[band])
  }
}
for (const [id, s] of Object.entries(FILTER_SHAPES)) {
  out.filter[id] = {
    out: s.out ? { type: s.out.type, values: sample(s.out.points) } : null,
    in: s.in ? { type: s.in.type, values: sample(s.in.points) } : null,
    noise: sample(s.noise),
  }
}
for (const id of PRESET_ORDER) {
  out.presets[id] = { matched: resolveShapes({ preset: id }, true), unmatched: resolveShapes({ preset: id }, false) }
}
out.presets.custom = resolveShapes({ preset: 'custom', volume_shape: 'overlap', eq_shape: 'three_band', filter_shape: 'hp_out' }, false)
for (const [a, b] of [[120, 120], [120, 126], [120, 131], [120, 60], [90, 180], [128, 100], [0, 120], [174, 87]]) {
  out.beats.push({ a, b, match: beatsMatch(a, b) })
}
out.camelot.colors = {}
for (let n = 1; n <= 12; n++) for (const side of ['A', 'B']) out.camelot.colors[`${n}${side}`] = camelot.camelotColor(`${n}${side}`)
out.camelot.pairs = []
for (const [a, b] of [['8A', '8A'], ['8A', '9A'], ['8A', '8B'], ['8A', '10A'], ['12B', '1B'], ['12B', '1A'], ['5A', '6B'], [null, '8A']]) {
  out.camelot.pairs.push({ a, b, compatible: camelot.keysCompatible(a, b) })
}
out.camelot.titles = [camelot.camelotTitle('9B', 'E', 'major'), camelot.camelotTitle('8A', 'A', 'minor'), camelot.camelotTitle(null), camelot.camelotTitle('11B', null)]

// Волна: детерминированные полосы → высоты столбцов из bandPaths/wavePath.
const wave = await lib('waveform.js')
let seed = 7
const rand = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648)
const bytes = (n, lo, hi) => Array.from({ length: n }, () => Math.floor(lo + rand() * (hi - lo)))
const b64 = (arr) => Buffer.from(arr).toString('base64')
const N = 97
const raw = {}
for (const band of ['lows', 'mids', 'highs']) {
  const peak = bytes(N, 40, 256)
  // Тело иногда выше гребня — как после округления на бэке.
  const rms = peak.map((v) => Math.min(255, Math.floor(v * (0.35 + rand() * 0.75))))
  raw[band] = { rms: b64(rms), peak: b64(peak) }
}
const decoded = wave.decodeBands(raw)
// Высоты из путей: ширина = columns − 1 (x столбца = его номер), высота 20000 — точность 1e-4.
function columnsOf(d, columns, mid) {
  const out = Array(columns).fill(null)
  const cmds = [...d.matchAll(/[ML](-?[\d.]+),(-?[\d.]+)/g)].map((m) => [+m[1], +m[2]])
  const top = cmds.slice(0, cmds.length / 2)
  for (const [x, y] of top) out[Math.round(x)] = +((mid - y) / mid).toFixed(4)
  return out
}
const windows = [
  { durationMs: 180000, fromMs: 0, toMs: 180000, columns: 40 },
  { durationMs: 180000, fromMs: 61234.5, toMs: 75000, columns: 33 },
  { durationMs: 180000, fromMs: -20000, toMs: 30000, columns: 25 },
  { durationMs: 180000, fromMs: 170000, toMs: 200000, columns: 30 },
]
out.waveform = { bands: raw, scale: decoded.scale, windows: [] }
for (const w of windows) {
  const paths = wave.bandPaths(decoded, w.durationMs, w.fromMs, w.toMs, w.columns - 1, 20000, w.columns)
  out.waveform.windows.push({ ...w, layers: paths.map((p) => ({ key: p.key, values: columnsOf(p.d, w.columns, 10000) })) })
}
const full = bytes(N, 0, 256)
const bass = bytes(N, 0, 256)
out.waveform.legacy = { full: b64(full), bass: b64(bass), windows: [] }
for (const w of windows) {
  const layer = (peaks) => columnsOf(wave.wavePath(wave.decodePeaks(b64(peaks)), w.durationMs, w.fromMs, w.toMs, w.columns - 1, 20000, w.columns), w.columns, 10000)
  out.waveform.legacy.windows.push({ ...w, full: layer(full), bass: layer(bass) })
}
out.waveform.barMs = [120, 128, 174.5, 0].map((bpm) => ({ bpm, ms: wave.barMs(bpm) }))
const beats = [500, 969, 1438, 1907, 2376, 2845]
out.waveform.snap = { beats, cases: [0, 734, 735, 1200, 1907, 2610, 2611, 9999, 1203.5].map((ms) => ({ ms, snapped: wave.snapToBeat(beats, ms) })) }

console.log(JSON.stringify(out))
