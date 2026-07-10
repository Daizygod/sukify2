<script setup>
import { ref, watch, computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import api from '@/lib/api'
import CollectionHero from '@/components/CollectionHero.vue'
import CoverLightbox from '@/components/CoverLightbox.vue'
import TrackRow from '@/components/TrackRow.vue'
import Icon from '@/components/Icon.vue'
import { trackCount, formatTotalDuration } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const player = usePlayerStore()
const auth = useAuthStore()
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

async function load(slug) {
  loading.value = true
  try {
    const { data } = await api.get(`/releases/${slug}`)
    release.value = data.data
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
  if (release.value?.tracks?.length) player.playContext(release.value.tracks, 0)
}
async function toggleLike() {
  if (!auth.isAuthenticated || !release.value) return
  if (release.value.is_liked) {
    release.value.is_liked = false
    await api.delete(`/releases/${release.value.id}/like`)
  } else {
    release.value.is_liked = true
    await api.post(`/releases/${release.value.id}/like`)
  }
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
        <RouterLink
          v-if="release.artist"
          :to="{ name: 'artist', params: { slug: release.artist.slug } }"
          class="release__artistchip"
        >
          <img v-if="release.artist.avatar_url" :src="release.artist.avatar_url" class="release__avatar" alt="" />
          <strong>{{ release.artist.name }}</strong>
        </RouterLink>
        <span>• {{ release.year }}</span>
        <span>• {{ trackCount(release.tracks.length) }},</span>
        <span class="muted">{{ formatTotalDuration(totalMs) }}</span>
      </template>
    </CollectionHero>

    <div class="release__body" :style="{ '--body-bg': release.colors?.background || '#222' }">
      <div class="release__actions">
        <div class="release__actions-left">
          <button class="play-btn play-btn--lg" @click="playAll"><Icon :name="isThisPlaying ? 'pause' : 'play'" :size="24" /></button>
          <button class="ctl-lg" title="В случайном порядке"><Icon name="shuffle" :size="22" /></button>
          <button
            v-if="auth.isAuthenticated"
            class="ctl-lg"
            :class="{ on: release.is_liked }"
            :title="release.is_liked ? 'Удалить из медиатеки' : 'Добавить в медиатеку'"
            @click="toggleLike"
          >
            <Icon :name="release.is_liked ? 'checkCircle' : 'plusCircle'" :size="26" />
          </button>
          <button class="ctl-lg" title="Скачать"><Icon name="downloadCircle" :size="26" /></button>
          <button class="ctl-lg" title="Открыть контекстное меню"><Icon name="more" :size="22" /></button>
        </div>
        <button class="release__view">
          <span>Список</span>
          <Icon name="list" :size="16" />
        </button>
      </div>

      <div class="tracklist">
        <div class="tracktable__head trackgrid trackgrid--album">
          <div>#</div>
          <div>Название</div>
          <div class="th--right"><Icon name="clock" :size="16" /></div>
        </div>
        <TrackRow
          v-for="(t, i) in release.tracks"
          :key="t.id"
          :track="{ ...t, release: { slug: release.slug, title: release.title }, cover: release.cover }"
          :index="i"
          variant="album"
          :context-tracks="release.tracks"
        />
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
.release__body {
  background: linear-gradient(180deg, color-mix(in srgb, var(--body-bg) 40%, #121212) 0, #121212 260px);
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
  font-size: 13px;
}
.release__view:hover {
  color: #fff;
}
</style>
