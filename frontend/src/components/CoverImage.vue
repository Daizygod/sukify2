<script setup>
import { computed } from 'vue'

const props = defineProps({
  cover: { type: Object, default: null }, // { 64:url, 160:url, ... }
  size: { type: Number, default: 300 },
  alt: { type: String, default: '' },
  rounded: { type: Boolean, default: false },
})

const SIZES = [64, 160, 300, 640, 1000]

const src = computed(() => {
  if (!props.cover) return null
  // Account for Hi-DPI: a 300px card is ~600 physical px on a 2x display, so
  // request a rendition large enough to stay crisp instead of upscaling.
  const dpr = Math.min(typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1, 2)
  const target = props.size * dpr
  const pick = SIZES.find((s) => s >= target) ?? SIZES[SIZES.length - 1]
  return props.cover[pick] || props.cover[640] || Object.values(props.cover)[0]
})
</script>

<template>
  <div class="cover" :class="{ 'cover--round': rounded }">
    <img v-if="src" :src="src" :alt="alt" loading="lazy" />
    <div v-else class="cover__ph">
      <svg viewBox="0 0 24 24" fill="currentColor" width="40%" height="40%">
        <path d="M12 3v10.55A4 4 0 1014 17V7h4V3h-6z" />
      </svg>
    </div>
  </div>
</template>

<style scoped>
.cover {
  position: relative;
  aspect-ratio: 1;
  width: 100%;
  background: #333;
  border-radius: 4px;
  overflow: hidden;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.35);
}
.cover--round {
  border-radius: 50%;
}
.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.cover__ph {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: #7a7a7a;
  background: linear-gradient(135deg, #333, #222);
}
</style>
