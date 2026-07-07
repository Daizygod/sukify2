<script setup>
import { ref, watch, computed } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/lib/api'
import CollectionHero from '@/components/CollectionHero.vue'
import TrackRow from '@/components/TrackRow.vue'
import Icon from '@/components/Icon.vue'
import { formatDuration } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const player = usePlayerStore()
const auth = useAuthStore()
const release = ref(null)
const loading = ref(true)

const totalDuration = computed(() => {
  const ms = (release.value?.tracks || []).reduce((a, t) => a + (t.duration_ms || 0), 0)
  const min = Math.round(ms / 60000)
  return `${min} min`
})

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

function playAll() {
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
    <CollectionHero
      :kind="release.type[0].toUpperCase() + release.type.slice(1)"
      :title="release.title"
      :cover="release.cover"
      :bg="release.colors?.background || '#333'"
    >
      <template #meta>
        <strong>{{ release.artist?.name }}</strong>
        <span>· {{ release.year }}</span>
        <span>· {{ release.tracks.length }} songs,</span>
        <span class="muted">{{ totalDuration }}</span>
      </template>
    </CollectionHero>

    <div class="release__body" :style="{ '--body-bg': release.colors?.background || '#222' }">
      <div class="release__actions">
        <button class="play-btn" @click="playAll"><Icon name="play" :size="24" /></button>
        <button v-if="auth.isAuthenticated" class="release__like" :class="{ on: release.is_liked }" @click="toggleLike">
          <Icon :name="release.is_liked ? 'heartFill' : 'heart'" :size="30" />
        </button>
      </div>

      <div class="tracklist">
        <div class="tracklist__head">
          <div>#</div>
          <div>Title</div>
          <div>Album</div>
          <div></div>
          <div><Icon name="clock" :size="16" /></div>
        </div>
        <TrackRow
          v-for="(t, i) in release.tracks"
          :key="t.id"
          :track="{ ...t, release: { slug: release.slug, title: release.title }, cover: release.cover }"
          :index="i"
          :show-cover="false"
          :context-tracks="release.tracks"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.release__body {
  background: linear-gradient(180deg, color-mix(in srgb, var(--body-bg) 40%, #121212) 0, #121212 260px);
  padding: 24px;
  min-height: 400px;
}
.release__actions {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-bottom: 24px;
}
.release__like {
  color: var(--text-subdued);
}
.release__like.on {
  color: var(--accent);
}
.tracklist__head {
  display: grid;
  grid-template-columns: 40px 1fr 22% 40px 60px;
  gap: 12px;
  padding: 4px 16px 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  color: var(--text-subdued);
  font-size: 13px;
  margin-bottom: 8px;
}
.tracklist__head > div:last-child {
  display: flex;
  justify-content: flex-end;
}
</style>
