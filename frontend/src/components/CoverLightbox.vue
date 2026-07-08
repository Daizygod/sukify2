<script setup>
import { watch, onUnmounted } from 'vue'

const props = defineProps({
  src: { type: String, default: '' },
  alt: { type: String, default: '' },
  open: { type: Boolean, default: false },
})
const emit = defineEmits(['close'])

function onKey(e) {
  if (e.key === 'Escape') emit('close')
}
watch(
  () => props.open,
  (v) => {
    if (v) window.addEventListener('keydown', onKey)
    else window.removeEventListener('keydown', onKey)
  }
)
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <Transition name="lb">
      <div v-if="open && src" class="lb" @click="emit('close')">
        <button class="lb__close" @click.stop="emit('close')" aria-label="Close">✕</button>
        <img :src="src" :alt="alt" class="lb__img" @click.stop />
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.lb {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(0, 0, 0, 0.9);
  display: grid;
  place-items: center;
  padding: 40px;
  cursor: zoom-out;
}
.lb__img {
  max-width: min(90vw, 90vh);
  max-height: 90vh;
  width: auto;
  height: auto;
  border-radius: 8px;
  box-shadow: 0 12px 60px rgba(0, 0, 0, 0.6);
  cursor: default;
}
.lb__close {
  position: absolute;
  top: 20px;
  right: 28px;
  font-size: 28px;
  color: #fff;
  opacity: 0.8;
}
.lb__close:hover {
  opacity: 1;
}
.lb-enter-active,
.lb-leave-active {
  transition: opacity 0.2s ease;
}
.lb-enter-from,
.lb-leave-to {
  opacity: 0;
}
</style>
