<script setup>
import { ref } from 'vue'
import Icon from './Icon.vue'

/**
 * «Добавить в медиатеку» — плюс в круге / зелёная галочка, как в Spotify.
 * Добавили — галочка «впрыгивает», от неё расходится зелёное кольцо.
 */
const props = defineProps({
  on: { type: Boolean, default: false },
  size: { type: Number, default: 16 },
  big: { type: Boolean, default: false },
})
const emit = defineEmits(['toggle'])

const popping = ref(false)
let popTimer = null
function onClick(e) {
  if (e.detail > 1) return
  const was = props.on
  emit('toggle')
  if (was) return
  popping.value = false
  clearTimeout(popTimer)
  requestAnimationFrame(() => {
    popping.value = true
    popTimer = setTimeout(() => (popping.value = false), 600)
  })
}
</script>

<template>
  <button
    class="save"
    :class="{ 'save--on': on, 'save--pop': popping }"
    :title="on ? 'Добавлено в медиатеку' : 'Добавить в медиатеку'"
    :aria-label="on ? 'Добавлено в медиатеку' : 'Добавить в медиатеку'"
    @click.stop="onClick"
  >
    <Icon :name="on ? (big ? 'checkCircleBig' : 'checkCircle') : big ? 'plusCircleBig' : 'plusCircle'" :size="size" />
  </button>
</template>

<style scoped>
.save {
  position: relative;
  color: var(--text-subdued);
  display: inline-grid;
  place-items: center;
  transition: transform 0.15s var(--ease-spotify), color 0.15s ease;
}
.save:hover {
  color: #fff;
  transform: scale(1.06);
}
.save--on,
.save--on:hover {
  color: var(--accent);
}
.save--pop svg {
  animation: save-pop 0.42s var(--ease-spotify);
}
.save--pop::after {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: 50%;
  border: 2px solid var(--accent);
  pointer-events: none;
  animation: save-ring 0.55s ease-out forwards;
}
@keyframes save-pop {
  0% {
    transform: scale(0.55);
  }
  55% {
    transform: scale(1.22);
  }
  100% {
    transform: scale(1);
  }
}
@keyframes save-ring {
  from {
    opacity: 0.9;
    transform: scale(0.6);
  }
  to {
    opacity: 0;
    transform: scale(1.9);
  }
}
</style>
