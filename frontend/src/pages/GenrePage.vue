<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/lib/api'
import MediaCard from '@/components/MediaCard.vue'
import { usePlayerStore } from '@/stores/player'

const route = useRoute()
const player = usePlayerStore()
const genre = ref('')
const artists = ref([])
const releases = ref([])

async function load(name) {
  const { data } = await api.get(`/genres/${encodeURIComponent(name)}`)
  genre.value = data.genre
  artists.value = data.artists
  releases.value = data.releases
}
watch(() => route.params.name, (n) => n && load(n), { immediate: true })

async function playRelease(r) {
  const { data } = await api.get(`/releases/${r.slug}`)
  if (data.data.tracks?.length) player.playContext(data.data.tracks, 0, { name: r.title })
}
</script>

<template>
  <div class="genre">
    <div class="genre__hero">
      <h1 class="genre__title">{{ genre }}</h1>
    </div>
    <div class="content-pad">
      <section v-if="artists.length" class="genre__section">
        <h2 class="section-title">Исполнители</h2>
        <div class="grid-cards">
          <MediaCard
            v-for="a in artists"
            :key="a.id"
            :to="{ name: 'artist', params: { slug: a.slug } }"
            :cover="a.avatar_url ? { 300: a.avatar_url } : null"
            :title="a.name"
            subtitle="Исполнитель"
            round
            :playable="false"
          />
        </div>
      </section>

      <section v-if="releases.length" class="genre__section">
        <h2 class="section-title">Релизы</h2>
        <div class="grid-cards">
          <MediaCard
            v-for="r in releases"
            :key="r.id"
            :to="{ name: 'release', params: { slug: r.slug } }"
            :cover="r.cover"
            :title="r.title"
            :subtitle="`${r.year || ''} · ${r.artist?.name || ''}`"
            @play="playRelease(r)"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.genre__hero {
  height: 200px;
  background: linear-gradient(135deg, #8d67ab 0%, #1e3264 100%);
  display: flex;
  align-items: flex-end;
  padding: 24px;
}
.genre__title {
  font-size: clamp(40px, 6vw, 72px);
  font-weight: 800;
  letter-spacing: -0.03em;
}
.genre__section {
  margin-bottom: 32px;
}
</style>
