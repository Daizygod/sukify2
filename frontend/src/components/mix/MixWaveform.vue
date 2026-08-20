<script setup>
/**
 * Редактор перекрытия: две волны — уходящий трек сверху, приходящий снизу.
 * Внутри подсвеченной области видна сетка долей и три кривые автоматизации:
 * громкость, эквалайзер, фильтр.
 */
import { computed, ref } from 'vue'
import Icon from '../Icon.vue'
import { decodePeaks, wavePath, beatLines, WAVE_COLORS } from '@/lib/mix/waveform'
import { VOLUME_SHAPES, EQ_SHAPES, FILTER_SHAPES, BAR_OPTIONS, valueAt, CURVE_COLORS } from '@/lib/mix/shapes'

const props = defineProps({
  from: { type: Object, default: null }, // анализ уходящего трека
  to: { type: Object, default: null }, // анализ приходящего
  outStartMs: { type: Number, required: true },
  inStartMs: { type: Number, required: true },
  lengthMs: { type: Number, required: true },
  shapes: { type: Object, required: true }, // { volume, eq, filter }
  bars: { type: Number, default: null },
  beatMatched: { type: Boolean, default: false },
  playhead: { type: Number, default: -1 }, // 0..1 внутри перекрытия, -1 — не играет
  previewing: { type: Boolean, default: false },
})
const emit = defineEmits(['update:outStartMs', 'update:inStartMs', 'preview', 'bars', 'nudge'])

const W = 1000 // внутренние координаты SVG, наружу растягивается по ширине
const H = 150

// Окно вокруг перекрытия: по половине его длины с каждой стороны — так видно,
// что было до и что будет после.
const padMs = computed(() => Math.max(props.lengthMs * 0.5, 1500))

function windowOf(startMs) {
  return { from: startMs - padMs.value, to: startMs + props.lengthMs + padMs.value }
}
const outWin = computed(() => windowOf(props.outStartMs))
const inWin = computed(() => windowOf(props.inStartMs))

/** Доля ширины, которую занимает само перекрытие: окно шире него на два поля. */
const overlapX = computed(() => (padMs.value / (props.lengthMs + padMs.value * 2)) * W)
const overlapW = computed(() => (props.lengthMs / (props.lengthMs + padMs.value * 2)) * W)

function paths(analysis, win) {
  if (!analysis) return { full: '', bass: '' }
  const d = analysis.duration_ms || 0
  return {
    full: wavePath(decodePeaks(analysis.waveform?.full), d, win.from, win.to, W, H),
    bass: wavePath(decodePeaks(analysis.waveform?.bass), d, win.from, win.to, W, H),
  }
}
const outPaths = computed(() => paths(props.from, outWin.value))
const inPaths = computed(() => paths(props.to, inWin.value))

const outBeats = computed(() =>
  beatLines(props.from?.beats, outWin.value.from, outWin.value.to, W)
)
const inBeats = computed(() => beatLines(props.to?.beats, inWin.value.from, inWin.value.to, W))

// --- Кривые ----------------------------------------------------------------
// Все три рисуем в координатах панели: 0 внизу, максимум вверху.

function polyline(points, map, steps = 120) {
  if (!points) return ''
  const out = []
  for (let i = 0; i <= steps; i++) {
    const t = i / steps
    const x = overlapX.value + overlapW.value * t
    out.push(`${x.toFixed(1)},${map(valueAt(points, t)).toFixed(1)}`)
  }

  return `M${out.join('L')}`
}

const volume = computed(() => VOLUME_SHAPES[props.shapes.volume] || VOLUME_SHAPES.crossfade)
const eq = computed(() => EQ_SHAPES[props.shapes.eq] || EQ_SHAPES.none)
const filter = computed(() => FILTER_SHAPES[props.shapes.filter] || FILTER_SHAPES.none)

const gainY = (v) => H * 0.08 + (1 - Math.max(0, Math.min(1, v))) * H * 0.84
// Эквалайзер: 0 дБ вверху, −40 дБ у самого низа.
const dbY = (v) => H * 0.08 + Math.max(0, Math.min(1, -v / 40)) * H * 0.84
// Частота — по логарифму, иначе весь ход прижат к правому краю.
const hzY = (v) => {
  const k = (Math.log10(Math.max(20, v)) - Math.log10(20)) / (Math.log10(20000) - Math.log10(20))
  return H * 0.08 + (1 - k) * H * 0.84
}

function curvesFor(side) {
  const list = []
  list.push({ key: 'volume', d: polyline(volume.value[side], gainY), color: CURVE_COLORS.volume })
  const bands = eq.value[side] || {}
  // Из трёх полос показываем ту, что реально ведут: рисовать три почти
  // одинаковые линии — только мешать.
  const band = bands.low || bands.mid || bands.high
  if (band) list.push({ key: 'eq', d: polyline(band, dbY), color: CURVE_COLORS.eq })
  const f = filter.value[side]
  if (f) list.push({ key: 'filter', d: polyline(f.points, hzY), color: CURVE_COLORS.filter })

  return list
}
const outCurves = computed(() => curvesFor('out'))
const inCurves = computed(() => curvesFor('in'))

// --- Перетаскивание --------------------------------------------------------

const dragging = ref(null)
const rootEl = ref(null)

function msPerPx() {
  const px = rootEl.value?.clientWidth || 400
  return (props.lengthMs + padMs.value * 2) / px
}

function onDown(side, e) {
  dragging.value = { side, x: e.clientX, start: side === 'out' ? props.outStartMs : props.inStartMs }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
  e.preventDefault()
}
function onMove(e) {
  if (!dragging.value) return
  // Тянем волну, а не рамку: движение вправо показывает более раннее место.
  const delta = (dragging.value.x - e.clientX) * msPerPx()
  const next = Math.max(0, Math.round(dragging.value.start + delta))
  emit(dragging.value.side === 'out' ? 'update:outStartMs' : 'update:inStartMs', next)
}
function onUp() {
  dragging.value = null
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
}

const barsOpen = ref(false)

/** «1 bar», а дальше «bars» — иначе плашка читается как опечатка. */
function barLabel(n) {
  return n === 1 ? '1 bar' : `${n} bars`
}
</script>

<template>
  <div ref="rootEl" class="mw" :class="{ 'mw--drag': dragging }">
    <div
      class="mw__pane mw__pane--out"
      role="slider"
      :aria-label="'Место перекрытия предыдущего трека'"
      :aria-valuenow="outStartMs"
      tabindex="0"
      title="Стрелки — на секунду, Shift — на десять, Alt — на десять миллисекунд, Home/End — к краям"
      @pointerdown="onDown('out', $event)"
      @keydown="emit('nudge', 'out', $event)"
    >
      <svg class="mw__svg" :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none">
        <!-- Подложка перекрытия лежит под волной: поверх она красила бы её. -->
        <rect class="mw__overlap" :x="overlapX" y="0" :width="overlapW" :height="H" rx="6" />
        <path :d="outPaths.full" :fill="WAVE_COLORS.full" />
        <path :d="outPaths.bass" :fill="WAVE_COLORS.bass" opacity="0.85" />
        <line
          v-for="(b, i) in outBeats"
          :key="i"
          :x1="b.x"
          :x2="b.x"
          y1="0"
          :y2="H"
          class="mw__beat"
          :class="{ 'mw__beat--strong': b.strong }"
        />
        <path
          v-for="c in outCurves"
          :key="c.key"
          :d="c.d"
          fill="none"
          :stroke="c.color"
          stroke-width="3"
          vector-effect="non-scaling-stroke"
        />
      </svg>
    </div>

    <button
      class="mw__preview"
      :aria-label="previewing ? 'Приостановить фрагмент перехода' : 'Послушать переход'"
      @click.stop="emit('preview')"
    >
      <Icon :name="previewing ? 'pause' : 'play'" :size="14" />
    </button>

    <div
      class="mw__pane mw__pane--in"
      role="slider"
      :aria-label="'Место перекрытия нового трека'"
      :aria-valuenow="inStartMs"
      tabindex="0"
      title="Стрелки — на секунду, Shift — на десять, Alt — на десять миллисекунд, Home/End — к краям"
      @pointerdown="onDown('in', $event)"
      @keydown="emit('nudge', 'in', $event)"
    >
      <svg class="mw__svg" :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none">
        <rect class="mw__overlap" :x="overlapX" y="0" :width="overlapW" :height="H" rx="6" />
        <path :d="inPaths.full" :fill="WAVE_COLORS.full" />
        <path :d="inPaths.bass" :fill="WAVE_COLORS.bass" opacity="0.85" />
        <line
          v-for="(b, i) in inBeats"
          :key="i"
          :x1="b.x"
          :x2="b.x"
          y1="0"
          :y2="H"
          class="mw__beat"
          :class="{ 'mw__beat--strong': b.strong }"
        />
        <path
          v-for="c in inCurves"
          :key="c.key"
          :d="c.d"
          fill="none"
          :stroke="c.color"
          stroke-width="3"
          vector-effect="non-scaling-stroke"
        />
      </svg>
    </div>

    <!-- Плейхед предпрослушки идёт через обе панели -->
    <div
      v-if="playhead >= 0"
      class="mw__playhead"
      :style="{ left: `calc(${((overlapX + overlapW * playhead) / W) * 100}%)` }"
    ></div>

    <!-- Длина в тактах: только когда темпы сходятся -->
    <div v-if="beatMatched && bars" class="mw__bars">
      <button class="mw__barsbtn" :aria-label="barLabel(bars)" @click.stop="barsOpen = !barsOpen">
        {{ barLabel(bars) }}
      </button>
      <div v-if="barsOpen" class="mw__barsmenu">
        <button
          v-for="b in BAR_OPTIONS"
          :key="b"
          class="mw__barsitem"
          :class="{ on: b === bars }"
          @click.stop="emit('bars', b); barsOpen = false"
        >
          {{ barLabel(b) }}
          <Icon v-if="b === bars" name="check" :size="14" />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mw {
  position: relative;
  /* Фона нет намеренно: панель под ней уже залита градиентом, и любая
     подложка выдала бы себя видимой границей. */
  user-select: none;
  /* Место под плашку тактов — иначе она ложится на строку нижнего трека. */
  padding-bottom: 20px;
}
.mw--drag,
.mw--drag * {
  cursor: grabbing;
}
.mw__pane {
  height: 150px;
  cursor: grab;
  touch-action: none;
}
.mw__svg {
  display: block;
  width: 100%;
  height: 100%;
}
.mw__overlap {
  fill: rgba(255, 255, 255, 0.07);
}
.mw__beat {
  stroke: rgba(255, 255, 255, 0.13);
  stroke-width: 1;
  vector-effect: non-scaling-stroke;
}
.mw__beat--strong {
  stroke: rgba(255, 255, 255, 0.3);
}
.mw__preview {
  position: absolute;
  left: 50%;
  top: 150px;
  transform: translate(-50%, -50%);
  z-index: 3;
  width: 40px;
  height: 28px;
  border-radius: 6px;
  background: rgba(60, 60, 60, 0.92);
  color: #fff;
  display: grid;
  place-items: center;
}
.mw__preview:hover {
  background: rgba(80, 80, 80, 0.95);
}
.mw__playhead {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--accent);
  pointer-events: none;
  z-index: 2;
}
.mw__bars {
  position: absolute;
  left: 50%;
  bottom: 0;
  transform: translateX(-50%);
  z-index: 4;
}
.mw__barsbtn {
  background: rgba(60, 60, 60, 0.95);
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  border-radius: 6px;
  padding: 5px 10px;
}
.mw__barsbtn:hover {
  background: rgba(85, 85, 85, 0.95);
}
.mw__barsmenu {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  top: calc(100% + 6px);
  background: #282828;
  border-radius: 6px;
  padding: 4px;
  min-width: 118px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.6);
}
.mw__barsitem {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 13px;
  color: #fff;
  text-align: left;
}
.mw__barsitem:hover {
  background: rgba(255, 255, 255, 0.1);
}
.mw__barsitem.on {
  color: var(--accent);
}
</style>
