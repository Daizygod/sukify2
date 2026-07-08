<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import DragBar from './DragBar.vue'
import LikeButton from './LikeButton.vue'
import { formatDuration } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useLibraryStore } from '@/stores/library'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'

const player = usePlayerStore()
const library = useLibraryStore()
const auth = useAuthStore()
const ui = useUiStore()

const track = computed(() => player.currentTrack)
const liked = computed(() => track.value && library.isLiked(track.value.id))

function onSeek(frac) {
  player.seek(frac * player.durationMs)
}
function onVol(frac) {
  player.setVolume(frac)
}
function cycleRepeat() {
  player.repeat = player.repeat === 'off' ? 'all' : player.repeat === 'all' ? 'one' : 'off'
}
</script>

<template>
  <footer class="player">
    <!-- Left: now playing -->
    <div class="player__now">
      <template v-if="track">
        <CoverImage :cover="track.cover" :size="56" class="player__cover" />
        <div class="player__meta">
          <div class="player__title">{{ track.title }}</div>
          <div class="player__artists">
            <template v-for="(a, i) in track.artists" :key="a.id">
              <RouterLink :to="{ name: 'artist', params: { slug: a.slug } }">{{ a.name }}</RouterLink><span v-if="i < track.artists.length - 1">, </span>
            </template>
          </div>
        </div>
        <LikeButton v-if="auth.isAuthenticated" :liked="liked" :size="16" @toggle="library.toggleLike(track)" />
      </template>
    </div>

    <!-- Center: controls -->
    <div class="player__center">
      <div class="player__controls">
        <button class="ctl" :class="{ on: player.shuffle }" @click="player.shuffle = !player.shuffle"><Icon name="shuffle" :size="18" /></button>
        <button class="ctl" @click="player.prev()"><Icon name="prev" :size="18" /></button>
        <button class="player__play" @click="player.togglePlay()">
          <Icon :name="player.isPlaying ? 'pause' : 'play'" :size="16" />
        </button>
        <button class="ctl" @click="player.next()"><Icon name="next" :size="18" /></button>
        <button class="ctl" :class="{ on: player.repeat !== 'off' }" @click="cycleRepeat">
          <Icon name="repeat" :size="18" />
          <span v-if="player.repeat === 'one'" class="ctl__one">1</span>
        </button>
      </div>
      <div class="player__progress">
        <span class="player__time">{{ formatDuration(player.positionMs) }}</span>
        <DragBar :value="player.progress" @input="onSeek" />
        <span class="player__time">{{ formatDuration(player.durationMs) }}</span>
      </div>
    </div>

    <!-- Right: view toggle + volume -->
    <div class="player__right">
      <button class="ctl" :class="{ on: ui.rightOpen }" title="Now playing view" @click="ui.toggleRight()"><Icon name="jam" :size="18" /></button>
      <button class="ctl" title="Connect to a device"><Icon name="devices" :size="18" /></button>
      <button class="ctl" @click="player.toggleMute()"><Icon name="volume" :size="18" /></button>
      <div class="player__vol"><DragBar :value="player.muted ? 0 : player.volume" @input="onVol" /></div>
    </div>
  </footer>
</template>

<style scoped>
.player {
  height: 100%;
  display: grid;
  grid-template-columns: 30% 40% 30%;
  align-items: center;
  padding: 0 16px;
  color: var(--text-subdued);
}
.player__now {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.player__cover {
  width: 56px;
  flex: 0 0 56px;
  border-radius: 4px;
}
.player__meta {
  min-width: 0;
}
.player__title {
  color: #fff;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.player__artists {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.player__artists a:hover {
  color: #fff;
  text-decoration: underline;
}
.player__center {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.player__controls {
  display: flex;
  align-items: center;
  gap: 20px;
}
.ctl {
  color: var(--text-subdued);
  position: relative;
  display: grid;
  place-items: center;
}
.ctl:hover {
  color: #fff;
}
.ctl.on {
  color: var(--accent);
}
.ctl__one {
  position: absolute;
  top: -6px;
  right: -6px;
  font-size: 9px;
  font-weight: 700;
}
.player__play {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #fff;
  color: #000;
  display: grid;
  place-items: center;
  transition: transform 0.1s ease;
}
.player__play:hover {
  transform: scale(1.06);
}
.player__progress {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  max-width: 620px;
}
.player__time {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  min-width: 40px;
  text-align: center;
}
.player__right {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: flex-end;
}
.player__vol {
  width: 100px;
  display: flex;
}
</style>
