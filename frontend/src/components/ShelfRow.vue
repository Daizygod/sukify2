<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import Icon from './Icon.vue'

/**
 * Полка карточек в одну строку, как на главной Spotify: лишние карточки
 * уходят вправо за край, по наведению появляются круглые стрелки, лента
 * едет плавно и «щёлкает» по карточкам. Карточки выплывают по очереди.
 */
const row = ref(null)
const canLeft = ref(false)
const canRight = ref(false)
let ro = null

function update() {
  const el = row.value
  if (!el) return
  canLeft.value = el.scrollLeft > 4
  canRight.value = el.scrollLeft + el.clientWidth < el.scrollWidth - 4
}
function page(dir) {
  const el = row.value
  if (!el) return
  el.scrollBy({ left: dir * el.clientWidth * 0.8, behavior: 'smooth' })
}

onMounted(async () => {
  await nextTick()
  update()
  ro = new ResizeObserver(update)
  ro.observe(row.value)
})
onUnmounted(() => ro?.disconnect())
</script>

<template>
  <div class="shelfrow">
    <div ref="row" class="shelfrow__track" @scroll.passive="update">
      <slot />
    </div>
    <Transition name="shelfarrow">
      <button v-if="canLeft" class="shelfrow__arrow shelfrow__arrow--left" title="Назад" @click="page(-1)">
        <Icon name="chevronRight" :size="16" />
      </button>
    </Transition>
    <Transition name="shelfarrow">
      <button v-if="canRight" class="shelfrow__arrow shelfrow__arrow--right" title="Вперёд" @click="page(1)">
        <Icon name="chevronRight" :size="16" />
      </button>
    </Transition>
  </div>
</template>

<style scoped>
.shelfrow {
  position: relative;
  margin: 0 -12px;
  container-type: inline-size;
}
.shelfrow__track {
  display: grid;
  grid-auto-flow: column;
  /* ~5½ карточек на средней ширине (как у Spotify), шире — крупнее, но не
     больше 224px: край следующей карточки подсказывает, что лента едет. */
  grid-auto-columns: clamp(164px, calc((100cqw - 8px) / 5.4), 224px);
  overflow-x: auto;
  overscroll-behavior-x: contain;
  scroll-snap-type: x mandatory;
  scroll-padding: 0 12px;
  scrollbar-width: none;
}
.shelfrow__track::-webkit-scrollbar {
  display: none;
}
.shelfrow__track > :deep(*) {
  scroll-snap-align: start;
  animation: shelf-in 0.5s var(--ease-spotify) backwards;
}
.shelfrow__track > :deep(:nth-child(2)) { animation-delay: 40ms; }
.shelfrow__track > :deep(:nth-child(3)) { animation-delay: 80ms; }
.shelfrow__track > :deep(:nth-child(4)) { animation-delay: 120ms; }
.shelfrow__track > :deep(:nth-child(5)) { animation-delay: 160ms; }
.shelfrow__track > :deep(:nth-child(6)) { animation-delay: 200ms; }
.shelfrow__track > :deep(:nth-child(n + 7)) { animation-delay: 240ms; }
@keyframes shelf-in {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
}
.shelfrow__arrow {
  position: absolute;
  /* По центру обложки, а не всей карточки с подписями. */
  top: calc(12px + (clamp(164px, calc((100cqw - 8px) / 5.4), 224px) - 24px) / 2);
  translate: 0 -50%;
  z-index: 3;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  background: rgba(18, 18, 18, 0.88);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.5);
  opacity: 0;
  transition: opacity 0.2s ease, transform 0.15s var(--ease-spotify), background 0.15s ease;
}
.shelfrow:hover .shelfrow__arrow {
  opacity: 1;
}
.shelfrow__arrow:hover {
  background: #2a2a2a;
  transform: scale(1.08);
}
.shelfrow__arrow--left {
  left: 4px;
}
.shelfrow__arrow--left svg {
  transform: rotate(180deg);
}
.shelfrow__arrow--right {
  right: 4px;
}
.shelfarrow-enter-active,
.shelfarrow-leave-active {
  transition: opacity 0.2s ease;
}
.shelfarrow-enter-from,
.shelfarrow-leave-to {
  opacity: 0 !important;
}
/* Телефон: лента под палец, без стрелок, карточки помельче. */
@media (max-width: 768px) {
  .shelfrow {
    margin: 0 -16px;
  }
  .shelfrow__track {
    grid-auto-columns: minmax(150px, 42vw);
    padding: 0 4px;
    scroll-snap-type: x proximity;
  }
  .shelfrow__arrow {
    display: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .shelfrow__track > :deep(*) {
    animation: none;
  }
}
</style>
