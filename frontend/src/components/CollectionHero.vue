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
        :class="{ 'hero__cover--round': round, 'hero__cover--zoom': zoomable }"
        @click="zoomable && emit('zoom')"
      >
        <slot name="cover">
          <CoverImage :cover="cover" :size="300" :rounded="round" :alt="title" />
        </slot>
        <div v-if="zoomable" class="hero__zoom-hint">Click to enlarge</div>
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
  background-image: linear-gradient(180deg, transparent 0, rgba(0, 0, 0, 0.5) 100%);
  padding: 0;
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
}
.hero__cover--round {
  border-radius: 50%;
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
</style>
