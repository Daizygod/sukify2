<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { RouterView } from 'vue-router'
import { usePlayerStore } from '@/stores/player'
import Sidebar from '@/components/Sidebar.vue'
import TopBar from '@/components/TopBar.vue'
import PlayerBar from '@/components/PlayerBar.vue'
import RightPanel from '@/components/RightPanel.vue'
import ContextMenu from '@/components/ContextMenu.vue'
import ToastHost from '@/components/ToastHost.vue'
import FullscreenView from '@/components/FullscreenView.vue'
import LyricsView from '@/components/LyricsView.vue'
import TransitionPicker from '@/components/TransitionPicker.vue'
import { useUiStore } from '@/stores/ui'
import { useDeviceStore } from '@/stores/devices'

const ui = useUiStore()
const devices = useDeviceStore()
const player = usePlayerStore()

// Глобальные горячие клавиши (не срабатывают в полях ввода).
function onHotkey(e) {
  const tag = e.target?.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || e.target?.isContentEditable) return

  if (e.ctrlKey && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    document.querySelector('.topbar__input')?.focus()
    return
  }
  switch (e.key) {
    case ' ':
      e.preventDefault()
      player.togglePlay()
      break
    case 'ArrowRight':
      e.preventDefault()
      e.ctrlKey ? player.next() : player.seek(Math.min(player.positionMs + 5000, player.durationMs))
      break
    case 'ArrowLeft':
      e.preventDefault()
      e.ctrlKey ? player.prev() : player.seek(Math.max(player.positionMs - 5000, 0))
      break
    case 'm':
    case 'M':
    case 'ь':
      player.toggleMute()
      break
    case 'f':
    case 'F':
    case 'а':
      ui.fullscreenOpen = !ui.fullscreenOpen
      break
    case 't':
    case 'T':
    case 'е':
      ui.lyricsOpen = !ui.lyricsOpen
      break
  }
}
onMounted(() => window.addEventListener('keydown', onHotkey))
onUnmounted(() => window.removeEventListener('keydown', onHotkey))

// Как в оригинале: нативное контекстное меню браузера выключено везде,
// кроме полей ввода (там оно нужно для вставки).
function onGlobalContextMenu(e) {
  const tag = e.target?.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || e.target?.isContentEditable) return
  e.preventDefault()
}
onMounted(() => document.addEventListener('contextmenu', onGlobalContextMenu))
onUnmounted(() => document.removeEventListener('contextmenu', onGlobalContextMenu))

const gridStyle = computed(() => ({
  gridTemplateColumns: ui.rightOpen
    ? `${ui.leftWidth}px 1fr ${ui.rightWidth}px`
    : `${ui.leftWidth}px 1fr`,
  // Connect bar adds a strip below the player when playback is remote.
  '--player-height': devices.isRemote ? '112px' : '88px',
}))

function startLeft(e) {
  const move = (ev) => ui.setLeftWidth(ev.clientX - 8)
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    document.body.style.cursor = ''
  }
  document.body.style.cursor = 'col-resize'
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
  e.preventDefault()
}

function startRight(e) {
  const move = (ev) => ui.setRightWidth(window.innerWidth - ev.clientX - 8)
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    document.body.style.cursor = ''
  }
  document.body.style.cursor = 'col-resize'
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
  e.preventDefault()
}
</script>

<template>
  <div class="app" :style="gridStyle">
    <TopBar class="app__top" />

    <div class="app__nav">
      <Sidebar />
      <div class="resizer resizer--edge-right" @pointerdown="startLeft"></div>
    </div>

    <main class="app__main">
      <div class="app__scroll">
        <RouterView />
      </div>
    </main>

    <div v-if="ui.rightOpen" class="app__right">
      <div class="resizer resizer--edge-left" @pointerdown="startRight"></div>
      <RightPanel />
    </div>

    <PlayerBar class="app__player" />
    <FullscreenView v-if="ui.fullscreenOpen" />
    <LyricsView v-if="ui.lyricsOpen" />
    <TransitionPicker v-if="ui.transitionFrom" />
    <ContextMenu />
    <ToastHost />
  </div>
</template>

<style scoped>
.app__nav,
.app__right {
  position: relative;
  min-height: 0;
}
.resizer {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 8px;
  z-index: 20;
  cursor: col-resize;
}
.resizer--edge-right {
  right: -8px;
}
.resizer--edge-left {
  left: -8px;
}
.resizer::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 3px;
  width: 2px;
  background: transparent;
  transition: background 0.15s ease;
}
.resizer:hover::after {
  background: var(--accent);
}
</style>
