<script setup>
import { ref, watch, computed } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/lib/api'
import TrackRow from '@/components/TrackRow.vue'
import MediaCard from '@/components/MediaCard.vue'
import Icon from '@/components/Icon.vue'
import { formatListeners, plural } from '@/lib/format'
import { RouterLink } from 'vue-router'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'
import { useLibraryStore } from '@/stores/library'

const route = useRoute()
const player = usePlayerStore()
const auth = useAuthStore()
const library = useLibraryStore()
const artist = ref(null)
const topTracks = ref([])
const liked = ref({ tracks: [], releases: [] })

const typeLabel = computed(() => ({
  album: 'альбом',
  single: 'сингл',
  ep: 'мини-альбом',
  compilation: 'сборник',
}))

async function load(slug) {
  const [{ data: a }, { data: tt }] = await Promise.all([
    api.get(`/artists/${slug}`),
    api.get(`/artists/${slug}/top-tracks`),
  ])
  artist.value = a.data
  topTracks.value = tt.data
  liked.value = { tracks: [], releases: [] }
  if (auth.isAuthenticated) {
    api.get(`/artists/${slug}/liked`).then(({ data }) => {
      liked.value = data
    }).catch(() => {})
  }
}
watch(() => route.params.slug, (s) => s && load(s), { immediate: true })

const isThisPlaying = computed(
  () => topTracks.value.some((t) => t.id === player.currentTrack?.id) && player.isPlaying
)
function playTop() {
  if (isThisPlaying.value) return player.togglePlay()
  if (topTracks.value.length) player.playContext(topTracks.value, 0, { name: artist.value?.name })
}
async function toggleFollow() {
  if (!auth.isAuthenticated) return
  await library.toggleFollow(artist.value)
}
</script>

<template>
  <div v-if="artist" class="artist">
    <div class="artist__hero" :style="{ '--a-bg': artist.colors?.background || '#333', backgroundImage: artist.banner_url ? `url(${artist.banner_url})` : null }">
      <div class="artist__hero-inner">
        <div class="artist__verified">
          <Icon name="verified" :size="24" class="artist__badge" />
          <span>Подтверждённый исполнитель</span>
        </div>
        <h1 class="artist__name">{{ artist.name }}</h1>
        <div class="artist__listeners">{{ formatListeners(artist.monthly_listeners) }}</div>
      </div>
    </div>

    <div class="artist__body">
      <div class="artist__actions">
        <button class="play-btn play-btn--lg" @click="playTop"><Icon :name="isThisPlaying ? 'pauseBig' : 'playBig'" :size="24" /></button>
        <button class="ctl-lg" :class="{ on: player.shuffle }" title="В случайном порядке" @click="player.setShuffle(true); playTop()"><Icon name="shuffleBig" :size="32" /></button>
        <button v-if="auth.isAuthenticated" class="artist__follow" @click="toggleFollow">
          {{ artist.is_followed ? 'Уже подписаны' : 'Подписаться' }}
        </button>
        <button class="ctl-lg" title="Открыть контекстное меню"><Icon name="moreBig" :size="32" /></button>
      </div>

      <div class="artist__cols">
        <section v-if="topTracks.length" class="artist__popular">
          <h2 class="section-title">Популярные треки</h2>
          <TrackRow
            v-for="(t, i) in topTracks"
            :key="t.id"
            :track="t"
            :index="i"
            variant="artist"
            :context-tracks="topTracks"
            :context-name="artist.name"
          />
        </section>

        <section v-if="liked.tracks.length" class="artist__youliked">
          <h2 class="section-title">Вам нравится</h2>
          <RouterLink :to="{ name: 'artist-liked', params: { slug: artist.slug } }" class="youliked">
            <div class="youliked__avatar">
              <img v-if="artist.avatar_url" :src="artist.avatar_url" alt="" />
              <div v-else class="youliked__heart"><Icon name="heartFill" :size="28" /></div>
            </div>
            <div class="youliked__meta">
              <div class="youliked__counts">
                {{ liked.tracks.length }} {{ plural(liked.tracks.length, 'трек', 'трека', 'треков') }}<template v-if="liked.releases.length"> • {{ liked.releases.length }} {{ plural(liked.releases.length, 'релиз', 'релиза', 'релизов') }}</template>
              </div>
              <div class="youliked__by">От: {{ artist.name }}</div>
            </div>
          </RouterLink>
        </section>
      </div>

      <section v-if="artist.releases?.length" style="margin-top:36px">
        <div class="artist__shelfhead">
          <h2 class="section-title">Дискография</h2>
          <span class="artist__all">Показать все</span>
        </div>
        <div class="grid-cards">
          <MediaCard
            v-for="r in artist.releases"
            :key="r.id"
            :to="{ name: 'release', params: { slug: r.slug } }"
            :cover="r.cover"
            :title="r.title"
            :subtitle="`${r.year || ''} • ${typeLabel[r.type] || r.type}`"
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
  padding: 24px 24px 28px;
}
.artist__verified {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 500;
}
.artist__badge {
  color: #4cb3ff;
  /* White disc behind the seal so the cut-out check reads white. */
  background: radial-gradient(circle, #fff 46%, transparent 47%);
}
.artist__name {
  font-size: clamp(48px, 9vw, 96px);
  font-weight: 800;
  letter-spacing: -0.04em;
  margin: 8px 0 16px;
  line-height: 1;
}
.artist__listeners {
  font-size: 15px;
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
.artist__follow {
  border: 1px solid var(--text-muted);
  color: #fff;
  border-radius: 999px;
  padding: 7px 15px;
  font-size: 13px;
  font-weight: 700;
}
.artist__follow:hover {
  border-color: #fff;
  transform: scale(1.02);
}
.artist__cols {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(260px, 340px);
  gap: 24px;
  align-items: start;
}
@media (max-width: 1100px) {
  .artist__cols {
    grid-template-columns: 1fr;
  }
}
.youliked {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px;
  border-radius: 8px;
}
.youliked:hover {
  background: rgba(255, 255, 255, 0.06);
}
.youliked__avatar {
  width: 88px;
  height: 88px;
  flex: 0 0 88px;
  border-radius: 50%;
  overflow: hidden;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
}
.youliked__avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.youliked__heart {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #450af5, #8e8ee5);
  color: #fff;
}
.youliked__counts {
  font-weight: 700;
  font-size: 15px;
}
.youliked__by {
  color: var(--text-subdued);
  font-size: 13px;
  margin-top: 4px;
}
.artist__shelfhead {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.artist__all {
  color: var(--text-subdued);
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
}
.artist__all:hover {
  text-decoration: underline;
}
</style>
