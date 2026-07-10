<script setup>
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import DragBar from './DragBar.vue'
import { formatDuration } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useLibraryStore } from '@/stores/library'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { useDeviceStore } from '@/stores/devices'
import { useToastStore } from '@/stores/toasts'
import { openMiniplayer } from '@/lib/miniplayer'

const player = usePlayerStore()
const library = useLibraryStore()
const auth = useAuthStore()
const ui = useUiStore()
const devices = useDeviceStore()
const toasts = useToastStore()

async function onMiniplayer() {
  const ok = await openMiniplayer(player).catch(() => false)
  if (!ok) toasts.show('Мини-плеер поддерживается в Chrome и Edge')
}

const track = computed(() => player.currentTrack)
const liked = computed(() => track.value && library.isLiked(track.value.id))

// --- Connect: when another device plays, the bar becomes a remote control ---
const remote = computed(() => devices.isRemote)
const rs = computed(() => devices.remoteState)

// Interpolate the remote position between state broadcasts.
const nowTick = ref(Date.now())
let tickTimer = null
onMounted(() => {
  tickTimer = setInterval(() => (nowTick.value = Date.now()), 1000)
})
onUnmounted(() => clearInterval(tickTimer))

const shownPlaying = computed(() => (remote.value ? !!rs.value?.playing : player.isPlaying))
const shownDuration = computed(() => (remote.value ? rs.value?.dur || 0 : player.durationMs))
const shownPosition = computed(() => {
  if (!remote.value) return player.positionMs
  const s = rs.value
  if (!s) return 0
  const drift = s.playing ? nowTick.value - s.receivedAt : 0
  return Math.min(s.pos + drift, s.dur || Infinity)
})
const shownProgress = computed(() =>
  shownDuration.value ? Math.min(shownPosition.value / shownDuration.value, 1) : 0
)
const shownVolume = computed(() =>
  remote.value ? rs.value?.vol ?? 0.8 : player.muted ? 0 : player.volume
)

function onTogglePlay() {
  if (remote.value) return devices.sendCommand(shownPlaying.value ? 'pause' : 'play')
  player.togglePlay()
}
function onNext() {
  remote.value ? devices.sendCommand('next') : player.next()
}
function onPrev() {
  remote.value ? devices.sendCommand('prev') : player.prev()
}
function onSeek(frac) {
  const ms = frac * shownDuration.value
  remote.value ? devices.sendCommand('seek', ms) : player.seek(ms)
}
function onVol(frac) {
  remote.value ? devices.sendCommand('volume', frac) : player.setVolume(frac)
}
function cycleRepeat() {
  player.repeat = player.repeat === 'off' ? 'all' : player.repeat === 'all' ? 'one' : 'off'
}
</script>

<template>
  <footer class="playerwrap">
    <div class="player">
      <!-- Left: now playing -->
      <div class="player__now">
        <template v-if="remote && rs?.track">
          <img v-if="rs.track.cover" :src="rs.track.cover" class="player__cover player__cover--img" alt="" />
          <div class="player__meta">
            <div class="player__title">{{ rs.track.title }}</div>
            <div class="player__artists">{{ rs.track.artists }}</div>
          </div>
        </template>
        <template v-else-if="track">
          <CoverImage :cover="track.cover" :size="56" class="player__cover" />
          <div class="player__meta">
            <div class="player__title">{{ track.title }}</div>
            <div class="player__artists">
              <template v-for="(a, i) in track.artists" :key="a.id">
                <RouterLink :to="{ name: 'artist', params: { slug: a.slug } }">{{ a.name }}</RouterLink><span v-if="i < track.artists.length - 1">, </span>
              </template>
            </div>
          </div>
          <button
            v-if="auth.isAuthenticated"
            class="ctl player__add"
            :class="{ on: liked }"
            :title="liked ? 'Добавлено в медиатеку' : 'Добавить в медиатеку'"
            @click="library.toggleLike(track)"
          >
            <Icon :name="liked ? 'checkCircle' : 'plusCircle'" :size="16" />
          </button>
        </template>
      </div>

      <!-- Center: controls -->
      <div class="player__center">
        <div class="player__controls">
          <button class="ctl ctl--dot" :class="{ on: player.shuffle }" title="В случайном порядке" @click="player.setShuffle(!player.shuffle)"><Icon name="shuffle" :size="16" /></button>
          <button class="ctl" title="Назад" @click="onPrev"><Icon name="prev" :size="16" /></button>
          <button class="player__play" @click="onTogglePlay">
            <Icon :name="shownPlaying ? 'pause' : 'play'" :size="16" />
          </button>
          <button class="ctl" title="Далее" @click="onNext"><Icon name="next" :size="16" /></button>
          <button class="ctl ctl--dot" :class="{ on: player.repeat !== 'off' }" title="Повторять" @click="cycleRepeat">
            <Icon name="repeat" :size="16" />
            <span v-if="player.repeat === 'one'" class="ctl__one">1</span>
          </button>
        </div>
        <div class="player__progress">
          <span class="player__time">{{ formatDuration(shownPosition) }}</span>
          <DragBar :value="shownProgress" @input="onSeek" />
          <span class="player__time">{{ formatDuration(shownDuration) }}</span>
        </div>
      </div>

      <!-- Right: view toggles + volume -->
      <div class="player__right">
        <button class="ctl ctl--dot" :class="{ on: ui.rightOpen && ui.rightView === 'nowplaying' }" title="Экран «Сейчас играет»" @click="ui.openRight('nowplaying')"><Icon name="nowplaying" :size="16" /></button>
        <button class="ctl ctl--dot" :class="{ on: ui.lyricsOpen }" title="Текст" @click="ui.lyricsOpen = !ui.lyricsOpen"><Icon name="lyrics" :size="16" /></button>
        <button class="ctl ctl--dot" :class="{ on: ui.rightOpen && ui.rightView === 'queue' }" title="Очередь" @click="ui.openRight('queue')"><Icon name="queue" :size="16" /></button>
        <button class="ctl ctl--dot" :class="{ on: (ui.rightOpen && ui.rightView === 'connect') || remote }" title="Подключение к устройству" @click="ui.openRight('connect')"><Icon name="devices" :size="16" /></button>
        <button class="ctl" title="Громкость" @click="remote ? null : player.toggleMute()"><Icon name="volume" :size="16" /></button>
        <div class="player__vol"><DragBar :value="shownVolume" @input="onVol" /></div>
        <button class="ctl" title="Мини-плеер" @click="onMiniplayer"><Icon name="miniplayer" :size="16" /></button>
        <button class="ctl" :class="{ on: ui.fullscreenOpen }" title="Полноэкранный режим" @click="ui.fullscreenOpen = !ui.fullscreenOpen"><Icon name="fullscreen" :size="16" /></button>
      </div>
    </div>

    <!-- Connect: playback happens elsewhere -->
    <button v-if="remote" class="connectbar" @click="ui.openRight('connect')">
      <Icon name="volume" :size="13" />
      <span>Играет здесь: {{ devices.activeDevice?.name || 'другое устройство' }}</span>
    </button>
  </footer>
</template>

<style scoped>
.playerwrap {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.player {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 30% 40% 30%;
  align-items: center;
  padding: 0 16px;
  color: var(--text-subdued);
}
.connectbar {
  height: 24px;
  flex: 0 0 24px;
  background: var(--accent);
  color: #000;
  font-size: 12px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  padding: 0 14px;
  border-radius: 4px;
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
.player__cover--img {
  height: 56px;
  object-fit: cover;
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
/* Small green dot under active toggles, as in the original player. */
.ctl--dot.on::after {
  content: '';
  position: absolute;
  bottom: -7px;
  left: 50%;
  translate: -50% 0;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--accent);
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
  gap: 13px;
  justify-content: flex-end;
}
.player__vol {
  width: 90px;
  display: flex;
}
</style>
