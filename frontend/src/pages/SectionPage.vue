<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/lib/api'
import MediaCard from '@/components/MediaCard.vue'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const player = usePlayerStore()
const auth = useAuthStore()

const data = ref({ recently_played: [], popular_tracks: [], new_releases: [] })
const loading = ref(true)

const meta = computed(() => {
  switch (route.params.key) {
    case 'recent':
      return { title: 'Ты недавно слушал(-а)', items: data.value.recently_played, kind: 'track' }
    case 'new':
      return { title: 'Новые релизы', items: data.value.new_releases, kind: 'release' }
    default:
      return {
        title: auth.user ? `Только для тебя, ${auth.user.name}` : 'Популярно сейчас',
        items: data.value.popular_tracks,
        kind: 'track',
      }
  }
})

onMounted(async () => {
  try {
    const { data: d } = await api.get('/home')
    data.value = d
  } finally {
    loading.value = false
  }
})

function playTrackCard(t) {
  if (player.currentTrack?.id === t.id) return player.togglePlay()
  player.playTrack(t, meta.value.items, { name: meta.value.title })
}
async function playRelease(r) {
  const { data: d } = await api.get(`/releases/${r.slug}`)
  if (d.data.tracks?.length) player.playContext(d.data.tracks, 0, { name: r.title })
}
</script>

<template>
  <div class="content-pad">
    <h1 class="section-title" style="font-size: 28px">{{ meta.title }}</h1>
    <div class="grid-cards">
      <template v-if="meta.kind === 'track'">
        <MediaCard
          v-for="t in meta.items"
          :key="t.id"
          :to="t.release ? { name: 'release', params: { slug: t.release.slug } } : '/'"
          :cover="t.cover"
          :title="t.title"
          :subtitle="t.artists.map((a) => a.name).join(', ')"
          :playing="player.currentTrack?.id === t.id && player.isPlaying"
          @play="playTrackCard(t)"
        />
      </template>
      <template v-else>
        <MediaCard
          v-for="r in meta.items"
          :key="r.id"
          :to="{ name: 'release', params: { slug: r.slug } }"
          :cover="r.cover"
          :title="r.title"
          :subtitle="`${r.year || ''} · ${r.artist?.name || ''}`"
          @play="playRelease(r)"
        />
      </template>
    </div>
    <p v-if="!loading && !meta.items.length" class="muted">Здесь пока пусто.</p>
  </div>
</template>
