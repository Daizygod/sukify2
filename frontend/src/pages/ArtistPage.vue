<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/lib/api'
import TrackRow from '@/components/TrackRow.vue'
import MediaCard from '@/components/MediaCard.vue'
import Icon from '@/components/Icon.vue'
import { formatListeners } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const player = usePlayerStore()
const auth = useAuthStore()
const artist = ref(null)
const topTracks = ref([])

async function load(slug) {
  const [{ data: a }, { data: tt }] = await Promise.all([
    api.get(`/artists/${slug}`),
    api.get(`/artists/${slug}/top-tracks`),
  ])
  artist.value = a.data
  topTracks.value = tt.data
}
watch(() => route.params.slug, (s) => s && load(s), { immediate: true })

function playTop() {
  if (topTracks.value.length) player.playContext(topTracks.value, 0)
}
async function toggleFollow() {
  if (!auth.isAuthenticated) return
  if (artist.value.is_followed) {
    artist.value.is_followed = false
    await api.delete(`/artists/${artist.value.slug}/follow`)
  } else {
    artist.value.is_followed = true
    await api.post(`/artists/${artist.value.slug}/follow`)
  }
}
</script>

<template>
  <div v-if="artist" class="artist">
    <div class="artist__hero" :style="{ '--a-bg': artist.colors?.background || '#333', backgroundImage: artist.banner_url ? `url(${artist.banner_url})` : null }">
      <div class="artist__hero-inner">
        <span class="artist__verified">Artist</span>
        <h1 class="artist__name">{{ artist.name }}</h1>
        <div class="artist__listeners">{{ formatListeners(artist.monthly_listeners) }}</div>
      </div>
    </div>

    <div class="artist__body">
      <div class="artist__actions">
        <button class="play-btn" @click="playTop"><Icon name="play" :size="24" /></button>
        <button v-if="auth.isAuthenticated" class="artist__follow" @click="toggleFollow">
          {{ artist.is_followed ? 'Following' : 'Follow' }}
        </button>
      </div>

      <section v-if="topTracks.length">
        <h2 class="section-title">Popular</h2>
        <TrackRow
          v-for="(t, i) in topTracks"
          :key="t.id"
          :track="t"
          :index="i"
          :context-tracks="topTracks"
        />
      </section>

      <section v-if="artist.releases?.length" style="margin-top:36px">
        <h2 class="section-title">Discography</h2>
        <div class="grid-cards">
          <MediaCard
            v-for="r in artist.releases"
            :key="r.id"
            :to="{ name: 'release', params: { slug: r.slug } }"
            :cover="r.cover"
            :title="r.title"
            :subtitle="`${r.year || ''} · ${r.type}`"
          />
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.artist__hero {
  height: 340px;
  background-size: cover;
  background-position: center 30%;
  background-color: var(--a-bg);
  display: flex;
  align-items: flex-end;
  position: relative;
}
.artist__hero::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent 40%, rgba(0, 0, 0, 0.6));
}
.artist__hero-inner {
  position: relative;
  z-index: 1;
  padding: 24px;
}
.artist__verified {
  font-size: 13px;
  font-weight: 600;
}
.artist__name {
  font-size: clamp(48px, 9vw, 96px);
  font-weight: 800;
  letter-spacing: -0.04em;
  margin: 8px 0 16px;
}
.artist__listeners {
  font-size: 14px;
}
.artist__body {
  background: linear-gradient(180deg, color-mix(in srgb, var(--a-bg, #333) 30%, #121212) 0, #121212 200px);
  padding: 24px;
}
.artist__actions {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-bottom: 24px;
}
.artist__follow {
  border: 1px solid var(--text-muted);
  color: #fff;
  border-radius: 999px;
  padding: 7px 15px;
  font-size: 14px;
  font-weight: 700;
}
.artist__follow:hover {
  border-color: #fff;
  transform: scale(1.02);
}
</style>
