<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import LikeButton from './LikeButton.vue'
import { formatDuration, formatCount } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useLibraryStore } from '@/stores/library'
import { useAuthStore } from '@/stores/auth'

const props = defineProps({
  track: { type: Object, required: true },
  index: { type: Number, default: null },
  showCover: { type: Boolean, default: true },
  showAlbum: { type: Boolean, default: true },
  contextTracks: { type: Array, default: null },
})

const player = usePlayerStore()
const library = useLibraryStore()
const auth = useAuthStore()

const isCurrent = computed(() => player.currentTrack?.id === props.track.id)
const isPlayingThis = computed(() => isCurrent.value && player.isPlaying)
const liked = computed(() => library.isLiked(props.track.id))

function play() {
  if (isCurrent.value) return player.togglePlay()
  player.playTrack(props.track, props.contextTracks)
}
function toggleLike() {
  if (!auth.isAuthenticated) return
  library.toggleLike(props.track)
}
</script>

<template>
  <div class="row" :class="{ 'row--active': isCurrent }" @dblclick="play">
    <div class="row__index">
      <button class="row__play" @click="play">
        <Icon v-if="isPlayingThis" name="pause" :size="16" />
        <Icon v-else name="play" :size="16" />
      </button>
      <span class="row__num" :class="{ 'row__num--green': isCurrent }">
        {{ index != null ? index + 1 : '' }}
      </span>
    </div>

    <div class="row__main">
      <CoverImage v-if="showCover" :cover="track.cover" :size="40" class="row__cover" />
      <div class="row__meta">
        <div class="row__title" :class="{ 'row__title--green': isCurrent }">{{ track.title }}</div>
        <div class="row__artists">
          <template v-for="(a, i) in track.artists" :key="a.id">
            <RouterLink :to="{ name: 'artist', params: { slug: a.slug } }" class="row__artist">{{ a.name }}</RouterLink><span v-if="i < track.artists.length - 1">, </span>
          </template>
        </div>
      </div>
    </div>

    <RouterLink
      v-if="showAlbum && track.release"
      :to="{ name: 'release', params: { slug: track.release.slug } }"
      class="row__album"
    >{{ track.release.title }}</RouterLink>

    <LikeButton
      v-if="auth.isAuthenticated"
      class="row__like"
      :class="{ 'row__like--visible': liked }"
      :liked="liked"
      :size="16"
      @toggle="toggleLike"
    />

    <div class="row__duration">{{ formatDuration(track.duration_ms) }}</div>
  </div>
</template>

<style scoped>
.row {
  display: grid;
  grid-template-columns: 40px 1fr 22% 40px 60px;
  align-items: center;
  gap: 12px;
  padding: 6px 16px;
  border-radius: 4px;
  color: var(--text-subdued);
  font-size: 14px;
}
.row:hover {
  background: rgba(255, 255, 255, 0.08);
}
.row__index {
  position: relative;
  display: grid;
  place-items: center;
  width: 24px;
}
.row__num {
  font-variant-numeric: tabular-nums;
}
.row__num--green {
  color: var(--accent);
}
.row__play {
  display: none;
  color: #fff;
}
.row:hover .row__play {
  display: block;
}
.row:hover .row__num {
  display: none;
}
.row__main {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.row__cover {
  width: 40px;
  flex: 0 0 40px;
}
.row__meta {
  min-width: 0;
}
.row__title {
  color: #fff;
  font-weight: 400;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.row__title--green {
  color: var(--accent);
}
.row__artists {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}
.row__artist:hover {
  text-decoration: underline;
  color: #fff;
}
.row__album {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.row__album:hover {
  text-decoration: underline;
  color: #fff;
}
.row__like {
  opacity: 0;
}
.row__like--visible {
  opacity: 1;
}
.row:hover .row__like {
  opacity: 1;
}
.row__duration {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
</style>
