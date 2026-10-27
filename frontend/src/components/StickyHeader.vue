<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import PlayButton from './PlayButton.vue'

/**
 * Шапка страницы, как у Spotify: пока виден hero, она прозрачна; по мере
 * прокрутки наливается цветом обложки, а когда большая кнопка ▶ уезжает
 * под неё — выплывают маленькая ▶ и название. Ставится прямо перед hero.
 */
const props = defineProps({
  title: { type: String, required: true },
  color: { type: String, default: '#333333' },
  contextKey: { type: String, default: '' },
  tracks: { type: [Array, Function], default: null },
  name: { type: String, default: '' },
})

const root = ref(null)
const fade = ref(0)
const reveal = ref(false)
let scroller = null
let raf = 0

function measure() {
  raf = 0
  if (!scroller || !root.value) return
  const y = scroller.scrollTop
  const hero = root.value.nextElementSibling
  const h = hero ? hero.offsetHeight : 320
  // Цвет набирается за вторую половину hero, ▶ с названием — после него.
  fade.value = Math.min(1, Math.max(0, (y - h * 0.4) / (h * 0.5)))
  reveal.value = y > h + 20
}
function onScroll() {
  if (!raf) raf = requestAnimationFrame(measure)
}

onMounted(() => {
  scroller = root.value?.closest('.app__scroll')
  scroller?.addEventListener('scroll', onScroll, { passive: true })
  measure()
})
onUnmounted(() => {
  scroller?.removeEventListener('scroll', onScroll)
  cancelAnimationFrame(raf)
})
</script>

<template>
  <div ref="root" class="sticky" :style="{ '--sh-color': color, '--sh-fade': fade }">
    <div class="sticky__bg"></div>
    <div class="sticky__row" :class="{ 'sticky__row--on': reveal }">
      <PlayButton
        v-if="contextKey && tracks"
        class="sticky__play"
        :context-key="contextKey"
        :tracks="tracks"
        :name="props.name || title"
        :size="20"
      />
      <span class="sticky__title">{{ title }}</span>
    </div>
  </div>
</template>

<style scoped>
.sticky {
  position: sticky;
  top: 0;
  /* Ниже окна текста (5) — оно лежит в той же колонке поверх страницы. */
  z-index: 4;
  height: 64px;
  margin-bottom: -64px;
  pointer-events: none;
}
.sticky__bg {
  position: absolute;
  inset: 0;
  background-color: color-mix(in srgb, var(--sh-color) 52%, #000);
  background-image: var(--grain), linear-gradient(180deg, rgba(255, 255, 255, 0.04), transparent);
  opacity: var(--sh-fade);
  box-shadow: 0 8px 18px rgba(0, 0, 0, calc(var(--sh-fade) * 0.45));
}
.sticky__row {
  position: relative;
  height: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 24px;
  opacity: 0;
  transform: translateY(10px);
  transition: opacity 0.22s ease, transform 0.32s var(--ease-spotify);
}
.sticky__row--on {
  opacity: 1;
  transform: none;
  pointer-events: auto;
}
.sticky__play {
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
}
.sticky__title {
  font-family: var(--font-title);
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
@media (max-width: 768px) {
  .sticky {
    display: none;
  }
}
</style>
