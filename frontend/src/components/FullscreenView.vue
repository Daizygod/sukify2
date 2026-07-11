<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'

const player = usePlayerStore()
const ui = useUiStore()

const track = computed(() => player.currentTrack)
const bg = computed(() => track.value?.release?.colors?.background || '#3b3054')

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

    <div v-if="track" class="fs__center">
      <CoverImage :cover="track.cover" :size="1000" class="fs__cover" />
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
  font-size: 15px;
}
.fs__actions {
  display: flex;
  gap: 8px;
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
</style>
