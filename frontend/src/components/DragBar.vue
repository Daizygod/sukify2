<script setup>
import { ref } from 'vue'

const props = defineProps({
  value: { type: Number, default: 0 }, // 0..1
})
const emit = defineEmits(['input', 'change'])

const el = ref(null)
const dragging = ref(false)

function fractionAt(clientX) {
  const r = el.value.getBoundingClientRect()
  return Math.min(Math.max((clientX - r.left) / r.width, 0), 1)
}
function onDown(e) {
  dragging.value = true
  emit('input', fractionAt(e.clientX))
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
  e.preventDefault()
}
function onMove(e) {
  if (dragging.value) emit('input', fractionAt(e.clientX))
}
function onUp(e) {
  if (!dragging.value) return
  dragging.value = false
  emit('change', fractionAt(e.clientX))
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
}
</script>

<template>
  <div ref="el" class="dragbar" :class="{ 'dragbar--active': dragging }" @pointerdown="onDown">
    <div class="dragbar__track">
      <div class="dragbar__fill" :style="{ width: value * 100 + '%' }"></div>
      <div class="dragbar__knob" :style="{ left: value * 100 + '%' }"></div>
    </div>
  </div>
</template>

<style scoped>
.dragbar {
  flex: 1;
  padding: 6px 0; /* bigger hit area */
  cursor: pointer;
  touch-action: none;
}
.dragbar__track {
  position: relative;
  height: 4px;
  background: #4d4d4d;
  border-radius: 2px;
}
.dragbar__fill {
  height: 100%;
  background: #fff;
  border-radius: 2px;
}
.dragbar:hover .dragbar__fill,
.dragbar--active .dragbar__fill {
  background: var(--accent);
}
.dragbar__knob {
  position: absolute;
  top: 50%;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #fff;
  transform: translate(-50%, -50%);
  opacity: 0;
  transition: opacity 0.12s ease;
}
.dragbar:hover .dragbar__knob,
.dragbar--active .dragbar__knob {
  opacity: 1;
}
</style>
