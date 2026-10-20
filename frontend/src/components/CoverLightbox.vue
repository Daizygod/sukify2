<script setup>
// Обложка во весь экран. Вылетает со своего места в шапке в центр экрана
// (чуть пружиня), а всё за ней обесцвечивается и размывается. Колесо, щипок
// и двойной клик/тап увеличивают, увеличенную можно таскать. Закрыть — клик
// мимо, ✕ или Esc: обложка улетает обратно в шапку.
import { ref, watch, nextTick, onUnmounted } from 'vue'

const props = defineProps({
  src: { type: String, default: '' },
  /** Копия поменьше, которая уже загружена в шапке — видна, пока грузится src. */
  preview: { type: String, default: '' },
  alt: { type: String, default: '' },
  open: { type: Boolean, default: false },
  /** Откуда вылетает: getBoundingClientRect() обложки в шапке. */
  from: { type: Object, default: null },
  round: { type: Boolean, default: false },
})
const emit = defineEmits(['close'])

const visible = ref(false) // оверлей в DOM
const shown = ref(false) // фон проявлен, обложка в центре
const box = ref(null)
const flight = ref('') // transform полёта
const flying = ref(false)

// Увеличение и сдвиг
const zoom = ref(1)
const panX = ref(0)
const panY = ref(0)
const zoomAnimated = ref(true)

const MAX_ZOOM = 5
const DOUBLE_ZOOM = 2.5

function targetRect() {
  const side = Math.min(window.innerWidth - 32, window.innerHeight - 96, 1000)
  return {
    left: (window.innerWidth - side) / 2,
    top: (window.innerHeight - side) / 2,
    width: side,
    height: side,
  }
}

/** transform, который ставит обложку из центра на место в шапке. */
function flightFromSource() {
  const f = props.from
  if (!f || !f.width) return 'scale(0.85)'
  const t = targetRect()
  const s = f.width / t.width
  const dx = f.left + f.width / 2 - (t.left + t.width / 2)
  const dy = f.top + f.height / 2 - (t.top + t.height / 2)
  return `translate(${dx}px, ${dy}px) scale(${s})`
}

async function openViewer() {
  resetZoom(false)
  visible.value = true
  flying.value = false
  flight.value = flightFromSource()
  await nextTick()
  void box.value?.offsetWidth // старт с места в шапке
  flying.value = true
  flight.value = ''
  shown.value = true
  window.addEventListener('keydown', onKey)
}

let closeTimer = null
function close() {
  if (!visible.value || !shown.value) return
  resetZoom(true)
  shown.value = false
  flying.value = true
  flight.value = flightFromSource()
  window.removeEventListener('keydown', onKey)
  clearTimeout(closeTimer)
  closeTimer = setTimeout(() => {
    visible.value = false
    emit('close')
  }, 320)
}

watch(
  () => props.open,
  (v) => {
    if (v && props.src) openViewer()
    else if (!v && visible.value) close()
  }
)

function onKey(e) {
  if (e.key === 'Escape') close()
}
onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  clearTimeout(closeTimer)
})

// ——— увеличение ———

function side() {
  return box.value?.clientWidth || targetRect().width
}
function clampPan() {
  const max = (side() * (zoom.value - 1)) / 2
  panX.value = Math.max(-max, Math.min(max, panX.value))
  panY.value = Math.max(-max, Math.min(max, panY.value))
}
function resetZoom(animated) {
  zoomAnimated.value = animated
  zoom.value = 1
  panX.value = 0
  panY.value = 0
}
/** Увеличить до z, удерживая точку (x, y) экрана под курсором/пальцами. */
function zoomAt(z, x, y, animated) {
  const r = box.value.getBoundingClientRect()
  // Точка относительно центра обложки в её «неувеличенных» координатах.
  const cx = r.left + r.width / 2
  const cy = r.top + r.height / 2
  const k = z / zoom.value
  panX.value = (panX.value - (x - cx)) * k + (x - cx)
  panY.value = (panY.value - (y - cy)) * k + (y - cy)
  zoom.value = z
  zoomAnimated.value = animated
  if (z <= 1.01) resetZoom(animated)
  else clampPan()
}

function onWheel(e) {
  const z = Math.max(1, Math.min(MAX_ZOOM, zoom.value * Math.exp(-e.deltaY * 0.0015)))
  zoomAt(z, e.clientX, e.clientY, false)
}

let lastTap = 0
function onDoubleTap(x, y) {
  if (zoom.value > 1.01) resetZoom(true)
  else zoomAt(DOUBLE_ZOOM, x, y, true)
}

// Указатели: один — тащим увеличенную, два — щипок.
const pointers = new Map()
let pinchStart = null
let dragStart = null
let moved = false

function onPointerDown(e) {
  e.currentTarget.setPointerCapture?.(e.pointerId)
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  moved = false
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()]
    pinchStart = { dist: Math.hypot(a.x - b.x, a.y - b.y), zoom: zoom.value }
    dragStart = null
  } else if (pointers.size === 1) {
    dragStart = { x: e.clientX, y: e.clientY, panX: panX.value, panY: panY.value }
  }
}
function onPointerMove(e) {
  if (!pointers.has(e.pointerId)) return
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  if (pointers.size === 2 && pinchStart) {
    const [a, b] = [...pointers.values()]
    const dist = Math.hypot(a.x - b.x, a.y - b.y)
    const z = Math.max(1, Math.min(MAX_ZOOM, (pinchStart.zoom * dist) / pinchStart.dist))
    zoomAt(z, (a.x + b.x) / 2, (a.y + b.y) / 2, false)
    moved = true
  } else if (dragStart && zoom.value > 1.01) {
    const dx = e.clientX - dragStart.x
    const dy = e.clientY - dragStart.y
    if (Math.abs(dx) + Math.abs(dy) > 3) moved = true
    zoomAnimated.value = false
    panX.value = dragStart.panX + dx
    panY.value = dragStart.panY + dy
    clampPan()
  }
}
function onPointerUp(e) {
  pointers.delete(e.pointerId)
  if (pointers.size < 2) pinchStart = null
  if (pointers.size === 0) {
    dragStart = null
    if (!moved) {
      const now = Date.now()
      if (now - lastTap < 300) {
        onDoubleTap(e.clientX, e.clientY)
        lastTap = 0
      } else lastTap = now
    }
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="lb" :class="{ 'lb--shown': shown }" @click="close" @wheel.prevent="onWheel">
      <!-- Фон проявляется и гаснет отдельно: сама обложка летит непрозрачной
           и садится ровно на своё место в шапке. -->
      <div class="lb__bg"></div>
      <button class="lb__close" @click.stop="close" aria-label="Закрыть">✕</button>
      <div
        ref="box"
        class="lb__box"
        :class="{ 'lb__box--fly': flying, 'lb__box--round': round && !shown }"
        :style="{ transform: flight || null }"
        @click.stop
      >
        <div
          class="lb__zoom"
          :class="{ 'lb__zoom--animated': zoomAnimated }"
          :style="{ transform: `translate(${panX}px, ${panY}px) scale(${zoom})`, cursor: zoom > 1.01 ? 'grab' : 'zoom-in' }"
          @pointerdown="onPointerDown"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
          @dragstart.prevent
        >
          <img v-if="preview" :src="preview" class="lb__img" alt="" draggable="false" />
          <img :src="src" :alt="alt" class="lb__img" draggable="false" />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.lb {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  cursor: zoom-out;
  touch-action: none;
}
/* Всё за обложкой — серое и чуть размытое; проявляется вместе с затемнением. */
.lb__bg {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  -webkit-backdrop-filter: grayscale(1) blur(6px);
  backdrop-filter: grayscale(1) blur(6px);
  opacity: 0;
  transition: opacity 0.3s ease;
}
.lb--shown .lb__bg {
  opacity: 1;
}
.lb__box {
  position: relative;
  width: min(calc(100vw - 32px), calc(100vh - 96px), 1000px);
  aspect-ratio: 1;
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 16px 70px rgba(0, 0, 0, 0.6);
  cursor: default;
}
/* Полёт с лёгким перелётом — как капля, которая плюхнулась на место. */
.lb__box--fly {
  transition:
    transform 0.46s cubic-bezier(0.34, 1.32, 0.5, 1),
    border-radius 0.3s ease;
}
.lb:not(.lb--shown) .lb__box--fly {
  transition:
    transform 0.3s cubic-bezier(0.4, 0, 0.2, 1),
    border-radius 0.3s ease;
}
.lb__box--round {
  border-radius: 50%;
}
.lb__zoom {
  position: absolute;
  inset: 0;
  transform-origin: center;
  touch-action: none;
}
.lb__zoom--animated {
  transition: transform 0.28s cubic-bezier(0.3, 0, 0.2, 1);
}
.lb__img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  user-select: none;
  -webkit-user-drag: none;
}
.lb__close {
  position: absolute;
  top: 20px;
  right: 24px;
  width: 44px;
  height: 44px;
  font-size: 24px;
  color: #fff;
  opacity: 0;
  z-index: 1;
  transition: opacity 0.3s ease;
}
.lb--shown .lb__close {
  opacity: 0.8;
}
.lb--shown .lb__close:hover {
  opacity: 1;
}
</style>
