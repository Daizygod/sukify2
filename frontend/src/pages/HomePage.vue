<script setup>
import { ref, onMounted, computed } from 'vue'
import api from '@/lib/api'
import MediaCard from '@/components/MediaCard.vue'
import { usePlayerStore } from '@/stores/player'

const player = usePlayerStore()

function isTrackPlaying(t) {
  return player.currentTrack?.id === t.id && player.isPlaying
}
function isReleasePlaying(r) {
  return player.currentTrack?.release?.slug === r.slug && player.isPlaying
}

function playTrackCard(t, list) {
  if (player.currentTrack?.id === t.id) return player.togglePlay()
  player.playTrack(t, list)
}
async function playRelease(r) {
  if (isReleasePlaying(r)) return player.togglePlay()
  const { data } = await api.get(`/releases/${r.slug}`)
  if (data.data.tracks?.length) player.playContext(data.data.tracks, 0)
}
const recentlyPlayed = ref([])
const popular = ref([])
const newReleases = ref([])
const loading = ref(true)

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 12) return 'Good morning'
  if (h < 18) return 'Good afternoon'
  return 'Good evening'
})

onMounted(async () => {
  try {
    const { data } = await api.get('/home')
    recentlyPlayed.value = data.recently_played
    popular.value = data.popular_tracks
    newReleases.value = data.new_releases
  } finally {
    loading.value = false
  }
})

function playTrack(track, list) {
  player.playTrack(track, list)
}
</script>

<template>
  <div class="content-pad home">
    <h1 class="home__greeting">{{ greeting }}</h1>

    <section v-if="recentlyPlayed.length" class="home__section">
      <h2 class="section-title">Jump back in</h2>
      <div class="grid-cards">
        <MediaCard
          v-for="t in recentlyPlayed"
          :key="t.id"
          :to="t.release ? { name: 'release', params: { slug: t.release.slug } } : '/'"
          :cover="t.cover"
          :title="t.title"
          :subtitle="t.artists.map((a) => a.name).join(', ')"
          :playing="isTrackPlaying(t)"
          @play="playTrackCard(t, recentlyPlayed)"
        />
      </div>
    </section>

    <section class="home__section">
      <h2 class="section-title">Popular right now</h2>
      <div class="grid-cards">
        <MediaCard
          v-for="t in popular"
          :key="t.id"
          :to="t.release ? { name: 'release', params: { slug: t.release.slug } } : '/'"
          :cover="t.cover"
          :title="t.title"
          :subtitle="t.artists.map((a) => a.name).join(', ')"
          :playing="isTrackPlaying(t)"
          @play="playTrackCard(t, popular)"
        />
      </div>
    </section>

    <section class="home__section">
      <h2 class="section-title">New releases</h2>
      <div class="grid-cards">
        <MediaCard
          v-for="r in newReleases"
          :key="r.id"
          :to="{ name: 'release', params: { slug: r.slug } }"
          :cover="r.cover"
          :title="r.title"
          :subtitle="`${r.year || ''} · ${r.artist?.name || ''}`"
          :playing="isReleasePlaying(r)"
          @play="playRelease(r)"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.home__greeting {
  font-size: 32px;
  font-weight: 700;
  letter-spacing: -0.03em;
  margin-bottom: 24px;
}
.home__section {
  margin-bottom: 36px;
}
</style>
