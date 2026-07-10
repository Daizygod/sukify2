<script setup>
import { computed } from 'vue'
import { RouterView } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import TopBar from '@/components/TopBar.vue'
import PlayerBar from '@/components/PlayerBar.vue'
import RightPanel from '@/components/RightPanel.vue'
import ContextMenu from '@/components/ContextMenu.vue'
import ToastHost from '@/components/ToastHost.vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()

const gridStyle = computed(() => ({
  gridTemplateColumns: ui.rightOpen
    ? `${ui.leftWidth}px 1fr ${ui.rightWidth}px`
    : `${ui.leftWidth}px 1fr`,
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
