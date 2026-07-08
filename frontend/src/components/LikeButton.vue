<script setup>
import { ref } from 'vue'
import Icon from './Icon.vue'

const props = defineProps({
  liked: { type: Boolean, default: false },
  size: { type: Number, default: 16 },
})
const emit = defineEmits(['toggle'])

const particles = ref([])
let pid = 0

function onClick() {
  const wasLiked = props.liked
  emit('toggle')
  if (!wasLiked) burst()
}

function burst() {
  const count = 8 + Math.floor(Math.random() * 4)
  for (let i = 0; i < count; i++) {
    const id = pid++
    const dx = (Math.random() * 2 - 1) * 34
    const dy = -(20 + Math.random() * 34)
    const rot = (Math.random() * 2 - 1) * 60
    const dur = 500 + Math.random() * 350
    const sz = 8 + Math.random() * 6
    particles.value.push({ id, dx, dy, rot, dur, sz })
    setTimeout(() => {
      particles.value = particles.value.filter((p) => p.id !== id)
    }, dur + 30)
  }
}
</script>

<template>
  <button
    class="likebtn"
    :class="{ 'likebtn--on': liked }"
    @click.stop="onClick"
    :aria-label="liked ? 'Remove from liked' : 'Add to liked'"
  >
    <Icon :name="liked ? 'heartFill' : 'heart'" :size="size" class="likebtn__icon" />
    <span
      v-for="p in particles"
      :key="p.id"
      class="likebtn__particle"
      :style="{
        '--dx': p.dx + 'px',
        '--dy': p.dy + 'px',
        '--rot': p.rot + 'deg',
        '--dur': p.dur + 'ms',
      }"
    >
      <Icon name="heartFill" :size="p.sz" />
    </span>
  </button>
</template>

<style scoped>
.likebtn {
  position: relative;
  color: var(--text-subdued);
  display: inline-grid;
  place-items: center;
  line-height: 0;
}
.likebtn:hover {
  color: #fff;
}
.likebtn--on {
  color: var(--accent);
}
.likebtn--on:hover {
  color: var(--accent);
}
.likebtn__icon {
  transition: transform 0.14s ease;
}
.likebtn--on .likebtn__icon {
  animation: like-pop 0.28s ease;
}
@keyframes like-pop {
  0% { transform: scale(1); }
  45% { transform: scale(1.35); }
  100% { transform: scale(1); }
}
.likebtn__particle {
  position: absolute;
  top: 50%;
  left: 50%;
  color: var(--accent);
  pointer-events: none;
  transform: translate(-50%, -50%);
  animation: like-fly var(--dur) cubic-bezier(0.2, 0.7, 0.3, 1) forwards;
}
@keyframes like-fly {
  0% {
    opacity: 1;
    transform: translate(-50%, -50%) scale(0.4) rotate(0deg);
  }
  100% {
    opacity: 0;
    transform: translate(calc(-50% + var(--dx)), calc(-50% + var(--dy))) scale(1) rotate(var(--rot));
  }
}
</style>
