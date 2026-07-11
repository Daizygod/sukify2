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
import { useMenuStore } from '@/stores/menu'
import { useDeviceStore } from '@/stores/devices'
import { useToastStore } from '@/stores/toasts'

const route = useRoute()
const player = usePlayerStore()
const auth = useAuthStore()
const library = useLibraryStore()
const menu = useMenuStore()
const devices = useDeviceStore()
const toasts = useToastStore()
const artist = ref(null)
const topTracks = ref([])
const liked = ref({ tracks: [], releases: [] })

// Популярные: 5 строк, «Ещё» раскрывает весь топ (как в Spotify).
const popularExpanded = ref(false)
const shownTracks = computed(() => (popularExpanded.value ? topTracks.value : topTracks.value.slice(0, 5)))

// Дискография: чипы-фильтры по типу релиза.
const discoTab = ref('popular')
const discoTabs = computed(() => {
  const rs = artist.value?.releases || []
  const tabs = [{ value: 'popular', label: 'Популярные релизы' }]
  if (rs.some((r) => r.type === 'album' || r.type === 'compilation')) tabs.push({ value: 'albums', label: 'Альбомы' })
  if (rs.some((r) => r.type === 'single' || r.type === 'ep')) tabs.push({ value: 'singles', label: 'Синглы и EP' })
  return tabs
})
const discoReleases = computed(() => {
  const rs = artist.value?.releases || []
  if (discoTab.value === 'albums') return rs.filter((r) => r.type === 'album' || r.type === 'compilation')
  if (discoTab.value === 'singles') return rs.filter((r) => r.type === 'single' || r.type === 'ep')
  return rs
})

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
  popularExpanded.value = false
  discoTab.value = 'popular'
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

/** «Вам нравится» → все лайкнутые треки исполнителя в очередь. */
function queueLiked() {
  if (devices.isRemote) {
    devices.sendCommand('queue-add', liked.value.tracks.map((t) => t.id))
    toasts.show(`Очередь отправлена на «${devices.activeDevice?.name || 'устройство'}»`)
    return
  }
  let n = 0
  for (const t of liked.value.tracks) if (player.addToQueue(t)) n++
  toasts.show(`В очередь добавлено: ${n}`)
}

function openLikedMenu(e) {
  menu.openEntityMenu(e, {
    type: 'artist-liked',
    slug: artist.value.slug,
    title: `Любимые треки: ${artist.value.name}`,
  })
}
</script>

<template>
  <div v-if="artist" class="artist">
    <div class="artist__hero" :style="{ '--a-bg': artist.colors?.background || '#333', backgroundImage: artist.banner_url ? `url(${artist.banner_url})` : null }">
      <div class="artist__hero-inner">
        <!-- Как в Spotify: имя, под ним бейдж подтверждения, ниже слушатели. -->
        <h1 class="artist__name">{{ artist.name }}</h1>
        <div class="artist__verified">
          <Icon name="verified" :size="24" class="artist__badge" />
          <span>Подтверждено Sukify</span>
        </div>
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
            v-for="(t, i) in shownTracks"
            :key="t.id"
            :track="t"
            :index="i"
            variant="artist"
            :context-tracks="topTracks"
            :context-name="artist.name"
          />
          <button v-if="topTracks.length > 5" class="artist__more" @click="popularExpanded = !popularExpanded">
            {{ popularExpanded ? 'Свернуть' : 'Ещё' }}
          </button>
        </section>

        <section v-if="liked.tracks.length" class="artist__youliked">
          <h2 class="section-title">Вам нравится</h2>
          <RouterLink
            :to="{ name: 'artist-liked', params: { slug: artist.slug } }"
            class="youliked"
            @contextmenu.prevent="openLikedMenu"
          >
            <div class="youliked__avatar">
              <img v-if="artist.avatar_url" :src="artist.avatar_url" alt="" />
              <div v-else class="youliked__heart"><Icon name="heartFill" :size="28" /></div>
              <Icon name="heartFill" :size="14" class="youliked__badge" />
            </div>
            <div class="youliked__meta">
              <div class="youliked__counts">
                {{ liked.tracks.length }} {{ plural(liked.tracks.length, 'трек', 'трека', 'треков') }}
              </div>
              <div class="youliked__by">От: {{ artist.name }}</div>
            </div>
            <button class="youliked__queue" title="Добавить в очередь" @click.prevent="queueLiked">
              <Icon name="queue" :size="16" />
            </button>
          </RouterLink>
        </section>
      </div>

      <section v-if="artist.releases?.length" style="margin-top:36px">
        <div class="artist__shelfhead">
          <h2 class="section-title">Дискография</h2>
          <span class="artist__all">Показать все</span>
        </div>
        <div v-if="discoTabs.length > 1" class="artist__chips">
          <button
            v-for="tab in discoTabs"
            :key="tab.value"
            class="artist__chip"
            :class="{ on: discoTab === tab.value }"
            @click="discoTab = tab.value"
          >{{ tab.label }}</button>
        </div>
        <div class="grid-cards">
          <MediaCard
            v-for="r in discoReleases"
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
  /* Баннер Spotify = ровно 40vh (замерено: 576@1440, 432@1080). */
  height: max(340px, 40vh);
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
  font-weight: 700;
  margin: 8px 0 2px;
}
.artist__badge {
  color: #4cb3ff;
  /* White disc behind the seal so the cut-out check reads white. */
  background: radial-gradient(circle, #fff 46%, transparent 47%);
}
.artist__name {
  font-size: clamp(48px, 9vw, 96px);
  font-weight: 800;
  letter-spacing: normal;
  margin: 0;
  line-height: 1.2;
}
.artist__listeners {
  font-size: 16px;
  line-height: 32px;
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
  padding: 5px 15px;
  font-size: 14px;
  font-weight: 700;
}
.artist__follow:hover {
  border-color: #fff;
  transform: scale(1.02);
}
.artist__cols {
  /* Spotify: таблица тянется, «Вам нравится» — фикс 343px, зазор 24. */
  display: grid;
  grid-template-columns: minmax(0, 1fr) 343px;
  gap: 24px;
  align-items: start;
}
@media (max-width: 1100px) {
  .artist__cols {
    grid-template-columns: 1fr;
  }
}
.youliked {
  position: relative;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px;
  border-radius: 8px;
}
.youliked__queue {
  position: absolute;
  right: 12px;
  top: 50%;
  translate: 0 -50%;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: rgba(0, 0, 0, 0.5);
  color: var(--text-subdued);
  opacity: 0;
  transition: opacity 0.15s ease;
}
.youliked:hover .youliked__queue {
  opacity: 1;
}
.youliked__queue:hover {
  color: #fff;
  transform: scale(1.05);
}
.youliked:hover {
  background: rgba(255, 255, 255, 0.06);
}
.youliked__avatar {
  position: relative;
  width: 88px;
  height: 88px;
  flex: 0 0 88px;
  border-radius: 50%;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.4);
}
.youliked__avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 50%;
}
/* Зелёное сердечко на нижнем правом крае круга — как у Spotify. */
.youliked__badge {
  position: absolute;
  right: 4px;
  bottom: 0;
  color: var(--accent);
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.6));
}
.youliked__heart {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #450af5, #8e8ee5);
  color: #fff;
  border-radius: 50%;
}
.youliked__counts {
  font-weight: 700;
  font-size: 16px;
}
.youliked__by {
  color: var(--text-subdued);
  font-size: 14px;
  margin-top: 6px;
}
.artist__shelfhead {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.artist__all {
  color: var(--text-subdued);
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
}
.artist__all:hover {
  text-decoration: underline;
}
.artist__more {
  color: rgba(255, 255, 255, 0.7);
  font-size: 14px;
  font-weight: 700;
  padding: 16px;
  text-align: left;
}
.artist__more:hover {
  color: #fff;
}
.artist__chips {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
}
.artist__chip {
  font-size: 14px;
  color: #fff;
  background: rgba(255, 255, 255, 0.07);
  border-radius: 999px;
  padding: 8px 16px;
  line-height: 1;
}
.artist__chip:hover {
  background: rgba(255, 255, 255, 0.12);
}
.artist__chip.on {
  background: #fff;
  color: #000;
}
</style>
