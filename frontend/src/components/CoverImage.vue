<script setup>
import { computed } from 'vue'

const props = defineProps({
  // Either a list of { size, url } (release/track covers) or a { size: url }
  // map (single FE-built covers, e.g. playlists).
  cover: { type: [Array, Object], default: null },
  // Rendered CSS size (px) — the `sizes` hint so the browser picks the right
  // rendition. Tiny renditions (64/160) are only for row thumbnails or the
  // phone media tray, never big cards.
  size: { type: Number, default: 300 },
  alt: { type: String, default: '' },
  rounded: { type: Boolean, default: false },
})

// Normalise both shapes to a [{ size, url }] list.
const list = computed(() => {
  const c = props.cover
  if (!c) return []
  const arr = Array.isArray(c)
    ? c
    : Object.entries(c).map(([size, url]) => ({ size: Number(size), url }))
  return arr
    .filter((x) => x && x.url && Number(x.size) > 0)
    .map((x) => ({ size: Number(x.size), url: x.url }))
    .sort((a, b) => a.size - b.size)
})

// Full responsive candidate set; the browser picks by display size × DPR.
const srcset = computed(() =>
  list.value.length ? list.value.map((x) => `${x.url} ${x.size}w`).join(', ') : null
)

const sizes = computed(() => `${props.size}px`)

// Fallback src (srcset-unsupported browsers): largest available.
const fallback = computed(() => (list.value.length ? list.value[list.value.length - 1].url : null))
</script>

<template>
  <div class="cover" :class="{ 'cover--round': rounded }">
    <img
      v-if="fallback"
      :src="fallback"
      :srcset="srcset"
      :sizes="sizes"
      :alt="alt"
      loading="lazy"
      decoding="async"
    />
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
