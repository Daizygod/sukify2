<script setup>
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'

/**
 * Бегущая строка, как у названия в плеере Spotify: если текст не влезает,
 * при наведении (и один раз после смены трека) он плавно уезжает влево до
 * конца, замирает и возвращается. Влезает — обычный текст без эффектов.
 */
const props = defineProps({
  // Смена ключа (id трека) — повод прокрутить строку один раз самому.
  watchKey: { type: [String, Number], default: null },
})

const box = ref(null)
const inner = ref(null)
const shift = ref(0)
const running = ref(false)
let ro = null
let autoTimer = null

function measure() {
  if (!box.value || !inner.value) return
  const over = inner.value.scrollWidth - box.value.clientWidth
  shift.value = over > 2 ? Math.ceil(over) : 0
}

function start() {
  if (shift.value > 0 && !running.value) running.value = true
}

/** Новый трек: даём глазу прочитать начало, потом показываем хвост. */
async function arm() {
  running.value = false
  clearTimeout(autoTimer)
  await nextTick()
  measure()
  if (props.watchKey != null) autoTimer = setTimeout(start, 1600)
}

onMounted(() => {
  ro = new ResizeObserver(measure)
  ro.observe(box.value)
  ro.observe(inner.value)
  arm()
})
onUnmounted(() => {
  ro?.disconnect()
  clearTimeout(autoTimer)
})

watch(() => props.watchKey, arm)
</script>

<template>
  <span
    ref="box"
    class="mq"
    :class="{ 'mq--over': shift > 0, 'mq--run': running }"
    :style="{ '--mq-shift': `${-shift}px`, '--mq-dur': `${2.4 + shift / 28}s` }"
    @mouseenter="start"
  >
    <span ref="inner" class="mq__inner" @animationend="running = false"><slot /></span>
  </span>
</template>

<style scoped>
.mq {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  min-width: 0;
}
.mq__inner {
  display: inline-block;
}
/* Не влезает — правый край растворяется, как у оригинала. */
.mq--over {
  -webkit-mask-image: linear-gradient(90deg, #000 calc(100% - 14px), transparent);
  mask-image: linear-gradient(90deg, #000 calc(100% - 14px), transparent);
}
.mq--run {
  -webkit-mask-image: linear-gradient(90deg, transparent 0, #000 10px, #000 calc(100% - 10px), transparent);
  mask-image: linear-gradient(90deg, transparent 0, #000 10px, #000 calc(100% - 10px), transparent);
}
.mq--run .mq__inner {
  animation: mq-slide var(--mq-dur) cubic-bezier(0.45, 0, 0.55, 1) 1;
}
@keyframes mq-slide {
  0%,
  12% {
    transform: translateX(0);
  }
  46%,
  62% {
    transform: translateX(var(--mq-shift));
  }
  100% {
    transform: translateX(0);
  }
}
@media (prefers-reduced-motion: reduce) {
  .mq--run .mq__inner {
    animation: none;
  }
}
</style>
