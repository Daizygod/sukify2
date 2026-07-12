<script setup>
import { computed } from 'vue'
import Icon from '../Icon.vue'
import CoverImage from '../CoverImage.vue'
import { usePlayerStore } from '@/stores/player'
import { useLibraryStore } from '@/stores/library'
import { useUiStore } from '@/stores/ui'

const player = usePlayerStore()
const library = useLibraryStore()
const ui = useUiStore()

const track = computed(() => player.currentTrack)
const liked = computed(() => track.value && library.isLiked(track.value.id))
const bg = computed(() => track.value?.release?.colors?.background || '#3a3a3a')
const progress = computed(() => (player.durationMs ? Math.min(player.positionMs / player.durationMs, 1) : 0))
const artists = computed(() => (track.value?.artists || []).map((a) => a.name).join(', '))
</script>

<template>
  <div v-if="track" class="mini" :style="{ '--mini-bg': bg }" @click="ui.mobileNowOpen = true">
    <CoverImage :cover="track.cover" :size="80" class="mini__cover" />
    <div class="mini__meta">
      <div class="mini__line">
        <span class="mini__title">{{ track.title }}</span>
        <span class="mini__sep"> • </span>
        <span class="mini__artists">{{ artists }}</span>
      </div>
      <div class="mini__device">Sukify Web Player</div>
    </div>
    <button class="mini__btn" :class="{ on: liked }" @click.stop="library.toggleLike(track)">
      <Icon :name="liked ? 'checkCircleBig' : 'plusCircleBig'" :size="24" />
    </button>
    <button class="mini__btn mini__play" @click.stop="player.togglePlay()">
      <Icon :name="player.isPlaying ? 'pauseBig' : 'playBig'" :size="24" />
    </button>
    <div class="mini__bar">
      <div class="mini__fill" :style="{ width: progress * 100 + '%' }"></div>
    </div>
  </div>
</template>

<style scoped>
.mini {
  position: fixed;
  left: 8px;
  right: 8px;
  /* Над нижней навигацией (56px) */
  bottom: calc(58px + env(safe-area-inset-bottom, 0px));
  z-index: 49;
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 8px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--mini-bg) 60%, #181818);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
  overflow: hidden;
}
.mini__cover {
  width: 40px;
  flex: 0 0 40px;
  border-radius: 4px;
}
.mini__meta {
  flex: 1;
  min-width: 0;
}
.mini__line {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}
.mini__title {
  color: #fff;
  font-weight: 700;
}
.mini__sep,
.mini__artists {
  color: rgba(255, 255, 255, 0.7);
}
.mini__device {
  font-size: 11px;
  color: var(--accent);
  margin-top: 1px;
}
.mini__btn {
  color: #fff;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  flex: 0 0 40px;
}
.mini__btn.on {
  color: var(--accent);
}
.mini__bar {
  position: absolute;
  left: 8px;
  right: 8px;
  bottom: 2px;
  height: 2px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.25);
}
.mini__fill {
  height: 100%;
  border-radius: 2px;
  background: #fff;
}
</style>
