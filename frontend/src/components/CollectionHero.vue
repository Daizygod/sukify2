<script setup>
import CoverImage from './CoverImage.vue'

defineProps({
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
        <h1 class="hero__title" :class="{ 'hero__title--xl': bigTitle }">{{ title }}</h1>
        <div class="hero__meta">
          <slot name="meta">{{ meta }}</slot>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.hero {
  background: linear-gradient(180deg, var(--hero-bg) 0%, rgba(0, 0, 0, 0.55) 100%);
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
  font-size: 13px;
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
  font-size: 13px;
  font-weight: 600;
}
.hero__title {
  font-weight: 800;
  letter-spacing: -0.04em;
  margin: 12px 0;
  line-height: 1.05;
}
.hero__title--xl {
  font-size: clamp(40px, 7vw, 88px);
}
.hero__meta {
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
</style>
