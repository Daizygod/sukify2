<script setup>
import { computed } from 'vue'
import CoverImage from './CoverImage.vue'

const props = defineProps({
  kind: { type: String, default: '' }, // "Album", "Playlist", ...
  title: { type: String, required: true },
  cover: { type: Object, default: null },
  meta: { type: String, default: '' },
  bg: { type: String, default: '#333333' },
  round: { type: Boolean, default: false },
  bigTitle: { type: Boolean, default: true },
  zoomable: { type: Boolean, default: false },
  /** Обложка сейчас открыта крупно — здесь её прячем, будто это она улетела. */
  zoomed: { type: Boolean, default: false },
})
const emit = defineEmits(['zoom'])

// Spotify shrinks the hero title to keep it on one line — approximate by length.
const titleSize = computed(() => {
  const len = props.title?.length || 0
  if (len <= 11) return 'clamp(40px, 7vw, 88px)'
  if (len <= 16) return 'clamp(36px, 5.2vw, 66px)'
  if (len <= 24) return 'clamp(32px, 4vw, 52px)'
  return 'clamp(28px, 3vw, 40px)'
})
</script>

<template>
  <div class="hero" :style="{ '--hero-bg': bg }">
    <div class="hero__inner">
      <div
        class="hero__cover"
        :class="{ 'hero__cover--round': round, 'hero__cover--zoom': zoomable, 'hero__cover--away': zoomed }"
        @click="zoomable && emit('zoom', $event.currentTarget.getBoundingClientRect())"
      >
        <slot name="cover">
          <CoverImage :cover="cover" :size="300" :rounded="round" :alt="title" />
        </slot>
        <div v-if="zoomable" class="hero__zoom-hint">Нажми, чтобы увеличить</div>
      </div>
      <div class="hero__text">
        <span v-if="kind" class="hero__kind">{{ kind }}</span>
        <h1 class="hero__title" :style="bigTitle ? { fontSize: titleSize } : null">{{ title }}</h1>
        <div class="hero__meta">
          <slot name="meta">{{ meta }}</slot>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hero {
  /* Как у Spotify: сплошной цвет обложки + лёгкое затемнение к низу.
     Блок ниже продолжает тот же цвет (60% чёрного → #121212) — шва нет. */
  background-color: var(--hero-bg);
  /* Объём: мягкий «свет» сверху-слева, затемнение к низу и зерно поверх —
     так цвет обложки выглядит как у Spotify, а не плоской плашкой. */
  background-image: var(--grain),
    radial-gradient(90% 130% at 12% 0%, rgba(255, 255, 255, 0.13), transparent 60%),
    linear-gradient(180deg, transparent 0, rgba(0, 0, 0, 0.5) 100%);
  padding: 0;
  transition: background-color 0.6s ease;
}
.hero__inner {
  display: flex;
  align-items: flex-end;
  gap: 24px;
  padding: 84px 24px 24px;
}
.hero__cover {
  width: 232px;
  height: 232px;
  flex: 0 0 232px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5);
  border-radius: 4px;
  overflow: hidden;
  animation: hero-cover-in 0.55s var(--ease-spotify) backwards;
  transition: transform 0.3s var(--ease-spotify), box-shadow 0.3s ease;
}
.hero__cover--zoom:hover {
  transform: scale(1.015);
  box-shadow: 0 14px 44px rgba(0, 0, 0, 0.6);
}
.hero__text {
  animation: hero-text-in 0.55s var(--ease-spotify) 0.06s backwards;
}
@keyframes hero-cover-in {
  from {
    opacity: 0;
    transform: scale(0.94);
  }
}
@keyframes hero-text-in {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
}
.hero__cover--round {
  border-radius: 50%;
}
.hero__cover--away {
  visibility: hidden;
}
.hero__cover--zoom {
  cursor: zoom-in;
  position: relative;
}
.hero__zoom-hint {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  opacity: 0;
  transition: opacity 0.18s ease;
  border-radius: 4px;
}
.hero__cover--zoom:hover .hero__zoom-hint {
  opacity: 1;
}
.hero__text {
  flex: 1;
  min-width: 0;
  padding-bottom: 8px;
}
.hero__kind {
  font-size: 14px;
  font-weight: 600;
}
.hero__title {
  font-weight: 800;
  letter-spacing: -0.04em;
  margin: 12px 0;
  line-height: 1.05;
  overflow-wrap: anywhere;
}
.hero__meta {
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
/* Мобильный: обложка по центру, текст под ней (как в приложении). */
@media (max-width: 768px) {
  .hero__inner {
    flex-direction: column;
    align-items: center;
    gap: 16px;
    padding: 24px 16px 12px;
  }
  .hero__cover {
    width: min(62vw, 260px);
    height: auto;
    aspect-ratio: 1;
    flex: none;
  }
  .hero__text {
    width: 100%;
    padding-bottom: 0;
  }
  .hero__title {
    font-size: 26px !important;
    margin: 6px 0;
    letter-spacing: -0.02em;
  }
  .hero__kind {
    display: none;
  }
}
</style>
