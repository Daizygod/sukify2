<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '@/lib/api'
import Icon from '@/components/Icon.vue'
import MediaCard from '@/components/MediaCard.vue'
import TrackRow from '@/components/TrackRow.vue'
import { usePlayerStore } from '@/stores/player'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()

const q = ref(route.params.q || '')
const results = ref({ tracks: [], releases: [], artists: [], playlists: [] })
const loading = ref(false)
let timer = null

function onInput() {
  clearTimeout(timer)
  timer = setTimeout(runSearch, 250)
}

async function runSearch() {
  const query = q.value.trim()
  if (!query) {
    results.value = { tracks: [], releases: [], artists: [], playlists: [] }
    return
  }
  loading.value = true
  try {
    const { data } = await api.get('/search', { params: { q: query } })
    results.value = data
  } finally {
    loading.value = false
  }
}

watch(() => route.params.q, (v) => {
  if (v && v !== q.value) {
    q.value = v
    runSearch()
  }
})
if (q.value) runSearch()
</script>

<template>
  <div class="content-pad search">
    <div class="search__box">
      <Icon name="search" :size="20" class="search__icon" />
      <input
        v-model="q"
        class="search__input"
        placeholder="Что хочешь включить?"
        @input="onInput"
        autofocus
      />
    </div>

    <div v-if="results.tracks.length" class="search__section">
      <h2 class="section-title">Треки</h2>
      <TrackRow
        v-for="(t, i) in results.tracks.slice(0, 5)"
        :key="t.id"
        :track="t"
        :index="i"
        :context-tracks="results.tracks"
      />
    </div>

    <div v-if="results.artists.length" class="search__section">
      <h2 class="section-title">Исполнители</h2>
      <div class="grid-cards">
        <MediaCard
          v-for="a in results.artists"
          :key="a.id"
          :to="{ name: 'artist', params: { slug: a.slug } }"
          :cover="a.avatar_url ? { 300: a.avatar_url } : null"
          :title="a.name"
          subtitle="Исполнитель"
          round
        />
      </div>
    </div>

    <div v-if="results.releases.length" class="search__section">
      <h2 class="section-title">Альбомы</h2>
      <div class="grid-cards">
        <MediaCard
          v-for="r in results.releases"
          :key="r.id"
          :to="{ name: 'release', params: { slug: r.slug } }"
          :cover="r.cover"
          :title="r.title"
          :subtitle="`${r.year || ''} · ${r.artist?.name || ''}`"
        />
      </div>
    </div>

    <div v-if="results.playlists.length" class="search__section">
      <h2 class="section-title">Плейлисты</h2>
      <div class="grid-cards">
        <MediaCard
          v-for="p in results.playlists"
          :key="p.id"
          :to="{ name: 'playlist', params: { id: p.id } }"
          :cover="p.cover_url ? { 300: p.cover_url } : null"
          :title="p.title"
          :subtitle="`Автор: ${p.owner?.name || ''}`"
        />
      </div>
    </div>

    <p v-if="q && !loading && !results.tracks.length && !results.artists.length && !results.releases.length" class="muted">
      По запросу «{{ q }}» ничего не найдено.
    </p>
  </div>
</template>

<style scoped>
.search__box {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #242424;
  border-radius: 999px;
  padding: 0 16px;
  max-width: 360px;
  margin-bottom: 28px;
}
.search__icon {
  color: var(--text-subdued);
}
.search__input {
  flex: 1;
  background: none;
  border: none;
  color: #fff;
  padding: 12px 0;
  font-size: 15px;
}
.search__input::placeholder {
  color: var(--text-muted);
}
.search__section {
  margin-bottom: 32px;
}
</style>
