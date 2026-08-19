<script setup>
import Icon from './Icon.vue'
import { usePlayContext } from '@/composables/usePlayContext'

/**
 * Зелёная кнопка «играть подборку». Всегда показывает настоящее состояние:
 * ▶ — включить/продолжить, ⏸ — пауза (в т.ч. когда играет пульт Connect).
 */
const props = defineProps({
  contextKey: { type: String, required: true },
  // Треки подборки: массив или (async) загрузчик — например, когда карточка
  // релиза на чужой странице ещё не знает его треклиста.
  tracks: { type: [Array, Function], default: () => [] },
  name: { type: String, default: '' },
  size: { type: Number, default: 24 },
})

const { isPlaying, toggle } = usePlayContext({
  key: () => props.contextKey,
  tracks: () => (typeof props.tracks === 'function' ? props.tracks() : props.tracks),
  name: () => props.name,
})

defineExpose({ isPlaying })
</script>

<template>
  <button
    class="play-btn"
    :class="{ 'play-btn--on': isPlaying }"
    :title="isPlaying ? 'Пауза' : 'Слушать'"
    :aria-label="isPlaying ? 'Пауза' : 'Слушать'"
    @click.stop="toggle"
  >
    <Icon :name="isPlaying ? 'pauseBig' : 'playBig'" :size="size" />
  </button>
</template>
