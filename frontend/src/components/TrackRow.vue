<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import { formatDuration, formatNumber, formatRelativeDate } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useLibraryStore } from '@/stores/library'
import { useAuthStore } from '@/stores/auth'
import { useMenuStore } from '@/stores/menu'

const props = defineProps({
  track: { type: Object, required: true },
  index: { type: Number, default: null },
  // 'playlist' — cover + album + date added; 'album' — bare titles; 'artist' — cover + play count.
  variant: { type: String, default: 'playlist' },
  contextTracks: { type: Array, default: null },
  contextName: { type: String, default: '' },
})

const player = usePlayerStore()
const library = useLibraryStore()
const auth = useAuthStore()
const menu = useMenuStore()

const isCurrent = computed(() => player.currentTrack?.id === props.track.id)
const isPlayingThis = computed(() => isCurrent.value && player.isPlaying)
const liked = computed(() => library.isLiked(props.track.id))
const addedAt = computed(() => props.track.added_at || props.track.liked_at)

function play() {
  if (isCurrent.value) return player.togglePlay()
  player.playTrack(props.track, props.contextTracks, { name: props.contextName })
}
function toggleLike() {
  if (!auth.isAuthenticated) return
  library.toggleLike(props.track)
}
</script>

<template>
  <div
    class="row trackgrid"
    :class="[`trackgrid--${variant}`, { 'row--active': isCurrent }]"
    @dblclick="play"
    @contextmenu.prevent="menu.openMenu($event, track)"
  >
    <div class="row__index">
      <button class="row__play" @click="play">
        <Icon v-if="isPlayingThis" name="pause" :size="14" />
        <Icon v-else name="play" :size="14" />
      </button>
      <span class="row__num" :class="{ 'row__num--green': isCurrent }">
        {{ index != null ? index + 1 : '' }}
      </span>
    </div>

    <div class="row__main">
      <CoverImage v-if="variant !== 'album'" :cover="track.cover" :size="40" class="row__cover" />
      <div class="row__meta">
        <div class="row__title" :class="{ 'row__title--green': isCurrent }">
          {{ track.title }}
          <span v-if="track.unofficial" class="row__unofficial" title="Нет на официальных площадках">эксклюзив</span>
        </div>
        <div class="row__artists">
          <template v-for="(a, i) in track.artists" :key="a.id">
            <RouterLink :to="{ name: 'artist', params: { slug: a.slug } }" class="row__artist">{{ a.name }}</RouterLink><span v-if="i < track.artists.length - 1">, </span>
          </template>
        </div>
      </div>
    </div>

    <RouterLink
      v-if="variant === 'playlist' && track.release"
      :to="{ name: 'release', params: { slug: track.release.slug } }"
      class="row__album"
    >{{ track.release.title }}</RouterLink>
    <span v-else-if="variant === 'playlist'"></span>

    <span v-if="variant === 'playlist'" class="row__date">{{ formatRelativeDate(addedAt) }}</span>

    <span v-if="variant === 'artist'" class="row__plays">{{ formatNumber(track.plays_count) }}</span>

    <div class="row__end">
      <button
        v-if="auth.isAuthenticated"
        class="row__add"
        :class="{ 'row__add--on': liked }"
        :title="liked ? 'Добавлено в медиатеку' : 'Добавить в медиатеку'"
        @click="toggleLike"
      >
        <Icon :name="liked ? 'checkCircle' : 'plusCircle'" :size="16" />
      </button>
      <span class="row__duration">{{ formatDuration(track.duration_ms) }}</span>
      <button class="row__more" title="Открыть контекстное меню" @click.stop="menu.openMenu($event, track)">
        <Icon name="more" :size="16" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.row {
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
  width: 16px;
}
.row__num {
  font-variant-numeric: tabular-nums;
  font-size: 15px;
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
  border-radius: 4px;
}
.row__meta {
  min-width: 0;
}
.row__title {
  color: #fff;
  font-weight: 500;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.row__title--green {
  color: var(--accent);
}
.row__unofficial {
  display: inline-block;
  vertical-align: 2px;
  background: rgba(255, 255, 255, 0.12);
  color: var(--text-subdued);
  font-size: 9px;
  font-weight: 800;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  border-radius: 3px;
  padding: 2px 5px;
  margin-left: 6px;
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
.row__album,
.row__date,
.row__plays {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}
.row__album:hover {
  text-decoration: underline;
  color: #fff;
}
.row__plays {
  font-variant-numeric: tabular-nums;
}
.row__end {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 14px;
}
.row__add {
  color: var(--text-subdued);
  display: grid;
  place-items: center;
  opacity: 0;
}
.row__add:hover {
  color: #fff;
  transform: scale(1.05);
}
.row__add--on {
  color: var(--accent);
  opacity: 1;
}
.row__add--on:hover {
  color: var(--accent-hover);
}
.row:hover .row__add {
  opacity: 1;
}
.row__duration {
  font-variant-numeric: tabular-nums;
  font-size: 14px;
  min-width: 40px;
  text-align: right;
}
.row__more {
  color: var(--text-subdued);
  display: grid;
  place-items: center;
  opacity: 0;
  width: 16px;
}
.row:hover .row__more {
  opacity: 1;
  color: #fff;
}
</style>
