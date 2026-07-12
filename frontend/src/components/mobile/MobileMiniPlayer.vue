<script setup>
import { computed } from 'vue'
import Icon from '../Icon.vue'
import CoverImage from '../CoverImage.vue'
import { useLibraryStore } from '@/stores/library'
import { useUiStore } from '@/stores/ui'
import { usePlaybackControls } from '@/composables/usePlaybackControls'

const library = useLibraryStore()
const ui = useUiStore()

const { player, devices, remote, localTrack, view, hasPlayback, shownPlaying, shownProgress, togglePlay } =
  usePlaybackControls()

const liked = computed(() => localTrack.value && library.isLiked(localTrack.value.id))
const bg = computed(() =>
  remote.value ? '#503750' : localTrack.value?.release?.colors?.background || '#3a3a3a'
)
const deviceLabel = computed(() =>
  remote.value ? `Играет: ${devices.activeDevice?.name || 'другое устройство'}` : 'Sukify Web Player'
)
</script>

<template>
  <div v-if="hasPlayback" class="mini" :style="{ '--mini-bg': bg }" @click="ui.mobileNowOpen = true">
    <img v-if="view.coverUrl" :src="view.coverUrl" class="mini__cover mini__cover--img" alt="" />
    <CoverImage v-else :cover="view.cover" :size="80" class="mini__cover" />
    <div class="mini__meta">
      <div class="mini__line">
        <span class="mini__title">{{ view.title }}</span>
        <span class="mini__sep"> • </span>
        <span class="mini__artists">{{ view.artists }}</span>
      </div>
      <div class="mini__device" :class="{ 'mini__device--remote': remote }">
        <Icon v-if="remote" name="devices" :size="11" />
        {{ deviceLabel }}
      </div>
    </div>
    <button v-if="localTrack" class="mini__btn" :class="{ on: liked }" @click.stop="library.toggleLike(localTrack)">
      <Icon :name="liked ? 'checkCircleBig' : 'plusCircleBig'" :size="24" />
    </button>
    <button class="mini__btn mini__play" @click.stop="togglePlay">
      <Icon :name="shownPlaying ? 'pauseBig' : 'playBig'" :size="24" />
    </button>
    <div class="mini__bar">
      <div class="mini__fill" :style="{ width: shownProgress * 100 + '%' }"></div>
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
.mini__cover--img {
  height: 40px;
  object-fit: cover;
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
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  overflow: hidden;
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
