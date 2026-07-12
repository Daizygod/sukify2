<script setup>
import { ref, watch, computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import api from '@/lib/api'
import CollectionHero from '@/components/CollectionHero.vue'
import CoverLightbox from '@/components/CoverLightbox.vue'
import TrackRow from '@/components/TrackRow.vue'
import TransitionSpot from '@/components/TransitionSpot.vue'
import Icon from '@/components/Icon.vue'
import { useTransitionInfo } from '@/lib/useTransitions'
import { trackCount, formatTotalDuration } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'
import { useLibraryStore } from '@/stores/library'
import { useToastStore } from '@/stores/toasts'
import { useUiStore } from '@/stores/ui'
import { downloadTracks } from '@/lib/download'
import HeroMenu from '@/components/HeroMenu.vue'

const route = useRoute()
const player = usePlayerStore()
const auth = useAuthStore()
const library = useLibraryStore()
const toasts = useToastStore()
const ui = useUiStore()

function playShuffled() {
  player.setShuffle(true)
  playAll()
}

const shareLink = computed(() =>
  release.value ? `${location.origin}/release/${release.value.slug}` : location.origin
)

async function download() {
  const list = release.value?.tracks || []
  toasts.show(`Скачиваю: ${list.length} трек(ов)…`)
  const n = await downloadTracks(list.map((t) => ({ ...t, release })))
  toasts.show(`Скачано файлов: ${n}`)
}
const release = ref(null)
const loading = ref(true)
const lightboxOpen = ref(false)

const kindLabel = computed(() => {
  const map = { album: 'Альбом', single: 'Сингл', ep: 'Мини-альбом', compilation: 'Сборник' }
  return map[release.value?.type] || 'Альбом'
})

const fullCover = computed(() => {
  if (release.value?.cover_original) return release.value.cover_original
  const c = release.value?.cover
  if (Array.isArray(c) && c.length) return [...c].sort((a, b) => b.size - a.size)[0].url
  return null
})

const totalMs = computed(() =>
  (release.value?.tracks || []).reduce((a, t) => a + (t.duration_ms || 0), 0)
)

const { info: tinfo, load: loadTinfo, keyFor } = useTransitionInfo()

async function load(slug) {
  loading.value = true
  try {
    const { data } = await api.get(`/releases/${slug}`)
    release.value = data.data
    loadTinfo(release.value.tracks || [])
  } finally {
    loading.value = false
  }
}
watch(() => route.params.slug, (s) => s && load(s), { immediate: true })

const isThisPlaying = computed(
  () => player.currentTrack?.release?.slug === release.value?.slug && player.isPlaying
)
function playAll() {
  if (isThisPlaying.value) return player.togglePlay()
  if (release.value?.tracks?.length) player.playContext(release.value.tracks, 0, { name: release.value.title })
}
async function toggleLike() {
  if (!auth.isAuthenticated || !release.value) return
  await library.toggleAlbumLike(release.value)
  toasts.show(release.value.is_liked ? 'Добавлено в медиатеку' : 'Удалено из медиатеки')
}
</script>

<template>
  <div v-if="release" class="release">
    <CoverLightbox :open="lightboxOpen" :src="fullCover" :alt="release.title" @close="lightboxOpen = false" />
    <CollectionHero
      :kind="kindLabel"
      :title="release.title"
      :cover="release.cover"
      :bg="release.colors?.background || '#333'"
      :zoomable="!!fullCover"
      @zoom="lightboxOpen = true"
    >
      <template #meta>
        <template v-for="(a, i) in (release.artists?.length ? release.artists : [release.artist]).filter(Boolean)" :key="a.id">
          <RouterLink :to="{ name: 'artist', params: { slug: a.slug } }" class="release__artistchip">
            <img v-if="a.avatar_url || a.banner_url" :src="a.avatar_url || a.banner_url" class="release__avatar" alt="" />
            <strong>{{ a.name }}</strong>
          </RouterLink>
          <span v-if="i < (release.artists?.length || 1) - 1" class="release__sep">,</span>
        </template>
        <span>• {{ release.year }}</span>
        <span>• {{ trackCount(release.tracks.length) }},</span>
        <span class="muted">{{ formatTotalDuration(totalMs) }}</span>
      </template>
    </CollectionHero>

    <div class="release__body" :style="{ '--body-bg': release.colors?.background || '#222' }">
      <div class="release__actions">
        <div class="release__actions-left">
          <button class="play-btn play-btn--lg" @click="playAll"><Icon :name="isThisPlaying ? 'pauseBig' : 'playBig'" :size="24" /></button>
          <button class="ctl-lg" :class="{ on: player.shuffle }" title="В случайном порядке" @click="playShuffled"><Icon name="shuffleBig" :size="32" /></button>
          <button
            v-if="auth.isAuthenticated"
            class="ctl-lg"
            :class="{ on: release.is_liked }"
            :title="release.is_liked ? 'Удалить из медиатеки' : 'Добавить в медиатеку'"
            @click="toggleLike"
          >
            <Icon :name="release.is_liked ? 'checkCircleBig' : 'plusCircleBig'" :size="32" />
          </button>
          <button class="ctl-lg" title="Скачать" @click="download"><Icon name="downloadCircle" :size="32" /></button>
          <HeroMenu :tracks="release.tracks" :link="shareLink" />
        </div>
        <button class="release__view" @click="ui.toggleListCompact()">
          <span>{{ ui.listCompact ? 'Компактный' : 'Список' }}</span>
          <Icon name="list" :size="16" />
        </button>
      </div>

      <div class="tracklist" :class="{ 'tracklist--compact': ui.listCompact }">
        <div class="tracktable__head trackgrid trackgrid--album">
          <div>#</div>
          <div>Название</div>
          <div class="th--right"><Icon name="clock" :size="16" /></div>
        </div>
        <template v-for="(t, i) in release.tracks" :key="t.id">
          <TrackRow
            :track="{ ...t, release: { slug: release.slug, title: release.title }, cover: release.cover }"
            :index="i"
            variant="album"
            :context-tracks="release.tracks"
            :context-name="release.title"
          />
          <TransitionSpot
            v-if="i < release.tracks.length - 1"
            :from="t"
            :to="release.tracks[i + 1]"
            :info="tinfo[keyFor(t, release.tracks[i + 1])]"
            @changed="loadTinfo(release.tracks)"
          />
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.release__artistchip {
  display: flex;
  align-items: center;
  gap: 6px;
}
.release__artistchip:hover strong {
  text-decoration: underline;
}
.release__avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  object-fit: cover;
}
.release__sep {
  margin-left: -4px;
}
.release__body {
  background-color: var(--body-bg, #222);
  background-image: linear-gradient(180deg, rgba(0, 0, 0, 0.6) 0, #121212 232px);
  padding: 24px;
  min-height: 400px;
}
.release__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.release__actions-left {
  display: flex;
  align-items: center;
  gap: 24px;
}
.play-btn--lg {
  width: 56px;
  height: 56px;
}
.ctl-lg {
  color: var(--text-subdued);
  display: grid;
  place-items: center;
}
.ctl-lg:hover {
  color: #fff;
  transform: scale(1.04);
}
.ctl-lg.on {
  color: var(--accent);
}
.ctl-lg.on:hover {
  color: var(--accent-hover);
}
.release__view {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-subdued);
  font-size: 14px;
}
.release__view:hover {
  color: #fff;
}
</style>
