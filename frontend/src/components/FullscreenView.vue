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
const artistNames = computed(() => (track.value?.artists || []).map((a) => a.name).join(', '))

function close() {
  ui.fullscreenOpen = false
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
}

function toggleBrowserFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
  else document.documentElement.requestFullscreen().catch(() => {})
}

function onKey(e) {
  if (e.key === 'Escape') close()
}
onMounted(() => window.addEventListener('keydown', onKey))
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <div class="fs" :style="{ '--fs-bg': bg }">
    <div class="fs__top">
      <span class="fs__context">{{ player.contextName || 'Сейчас играет' }}</span>
      <div class="fs__actions">
        <button class="fs__btn" title="Во весь экран браузера" @click="toggleBrowserFullscreen">
          <Icon name="fullscreen" :size="16" />
        </button>
        <button class="fs__btn" title="Свернуть" @click="close">
          <Icon name="plus" :size="18" style="transform: rotate(45deg)" />
        </button>
      </div>
    </div>

    <div v-if="track" class="fs__center">
      <CoverImage :cover="track.cover" :size="640" class="fs__cover" />
      <div class="fs__meta">
        <div class="fs__title">{{ track.title }}</div>
        <div class="fs__artists">{{ artistNames }}</div>
      </div>
    </div>
    <div v-else class="fs__center">
      <p class="muted">Включи что-нибудь — здесь будет красиво.</p>
    </div>
  </div>
</template>

<style scoped>
.fs {
  position: fixed;
  top: var(--topbar-height);
  bottom: var(--player-height);
  left: 8px;
  right: 8px;
  z-index: 60;
  border-radius: var(--radius);
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
  width: min(44vh, 480px);
  border-radius: 12px;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.5);
}
.fs__meta {
  text-align: center;
}
.fs__title {
  font-size: clamp(28px, 4vw, 48px);
  font-weight: 800;
  letter-spacing: -0.02em;
}
.fs__artists {
  color: rgba(255, 255, 255, 0.75);
  font-size: 18px;
  margin-top: 8px;
}
</style>
