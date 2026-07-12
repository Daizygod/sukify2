<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'
import { useLyrics } from '@/composables/useLyrics'

const player = usePlayerStore()
const ui = useUiStore()

const track = computed(() => player.currentTrack)
const bg = computed(() => track.value?.release?.colors?.background || '#3b3054')

// Текст трека: если найден — обложка уезжает влево, справа появляются строки.
const { lyrics, lines, activeIndex, hasLyrics } = useLyrics()
const listEl = ref(null)

watch(activeIndex, async (i) => {
  if (i < 0) return
  await nextTick()
  const el = listEl.value?.querySelector(`[data-i="${i}"]`)
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
})

function seekTo(line) {
  player.seek(line.ms)
}

function close() {
  ui.fullscreenOpen = false
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
}

function onKey(e) {
  if (e.key === 'Escape') close()
}
function onFsChange() {
  // Вышли из браузерного fullscreen (Esc/F11) — закрываем оверлей.
  if (!document.fullscreenElement) ui.fullscreenOpen = false
}
onMounted(() => {
  // Как F11: сразу разворачиваемся на весь экран.
  document.documentElement.requestFullscreen?.().catch(() => {})
  window.addEventListener('keydown', onKey)
  document.addEventListener('fullscreenchange', onFsChange)
})
onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  document.removeEventListener('fullscreenchange', onFsChange)
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
})
</script>

<template>
  <div class="fs" :style="{ '--fs-bg': bg }">
    <div class="fs__top">
      <span class="fs__context">{{ player.contextName || 'Сейчас играет' }}</span>
      <button class="fs__btn" title="Свернуть" @click="close">
        <Icon name="plus" :size="18" style="transform: rotate(45deg)" />
      </button>
    </div>

    <div v-if="track" class="fs__center" :class="{ 'fs__center--lyrics': hasLyrics }">
      <CoverImage :cover="track.cover" :size="1000" class="fs__cover" />

      <div v-if="hasLyrics" ref="listEl" class="fs__lyrics">
        <template v-if="lines.length">
          <p
            v-for="(l, i) in lines"
            :key="i"
            class="fs__line"
            :class="{ 'fs__line--past': i < activeIndex, 'fs__line--active': i === activeIndex }"
            :data-i="i"
            @click="seekTo(l)"
          >
            {{ l.text || '♪' }}
          </p>
        </template>
        <template v-else>
          <p v-for="(l, i) in lyrics.plain.split('\n')" :key="i" class="fs__line fs__line--static">
            {{ l || ' ' }}
          </p>
        </template>
      </div>
    </div>
    <div v-else class="fs__center">
      <p class="muted">Включи что-нибудь — здесь будет красиво.</p>
    </div>
  </div>
</template>

<style scoped>
.fs {
  /* Поверх всего, включая верхнюю панель — остаётся только плеер снизу. */
  position: fixed;
  top: 0;
  bottom: var(--player-height);
  left: 0;
  right: 0;
  z-index: 60;
  background: linear-gradient(180deg, var(--fs-bg) 0%, color-mix(in srgb, var(--fs-bg) 40%, #000) 100%);
  display: flex;
  flex-direction: column;
  padding: 24px 32px;
  overflow: hidden;
}
.fs__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.fs__context {
  font-weight: 700;
  font-size: 16px;
}
.fs__btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: rgba(255, 255, 255, 0.8);
}
.fs__btn:hover {
  color: #fff;
  background: rgba(0, 0, 0, 0.2);
}
.fs__center {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 28px;
}
.fs__cover {
  width: min(62vh, 720px);
  border-radius: 10px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.55);
}
/* Есть текст: обложка слева, строки справа. */
.fs__center--lyrics {
  flex-direction: row;
  align-items: center;
  justify-content: center;
  gap: clamp(32px, 6vw, 96px);
  padding: 24px 0;
}
.fs__center--lyrics .fs__cover {
  width: min(56vh, 42vw, 640px);
  flex-shrink: 0;
}
.fs__lyrics {
  align-self: stretch;
  flex: 0 1 760px;
  min-width: 0;
  overflow-y: auto;
  /* Пустота сверху/снизу, чтобы активная строка держалась по центру. */
  padding: 34vh 8px 34vh 0;
  scrollbar-width: none;
  -webkit-mask-image: linear-gradient(180deg, transparent 0, #000 15%, #000 85%, transparent 100%);
  mask-image: linear-gradient(180deg, transparent 0, #000 15%, #000 85%, transparent 100%);
}
.fs__lyrics::-webkit-scrollbar {
  display: none;
}
.fs__line {
  font-size: clamp(24px, 2.6vw, 40px);
  font-weight: 800;
  letter-spacing: -0.01em;
  line-height: 1.3;
  margin-bottom: 20px;
  color: rgba(0, 0, 0, 0.6);
  cursor: pointer;
  transition: color 0.2s ease;
}
.fs__line:hover {
  color: #fff;
}
.fs__line--past {
  color: rgba(255, 255, 255, 0.55);
}
.fs__line--active {
  color: #fff;
}
.fs__line--static {
  color: rgba(255, 255, 255, 0.85);
  cursor: default;
}
</style>
