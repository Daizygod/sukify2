<script setup>
import { ref, watch, computed, onMounted } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import api from '@/lib/api'
import Icon from '@/components/Icon.vue'
import MediaCard from '@/components/MediaCard.vue'
import TrackRow from '@/components/TrackRow.vue'
import { usePlayerStore } from '@/stores/player'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()

// Мобильное поле поиска (на десктопе поиск живёт в топбаре).
const mq = ref(route.params.q || '')
let mTimer
function onMobileSearch() {
  clearTimeout(mTimer)
  mTimer = setTimeout(() => {
    const v = mq.value.trim()
    router.replace(v ? { name: 'search-query', params: { q: v } } : { name: 'search' })
  }, 350)
}

const q = ref(route.params.q || '')
const results = ref({ tracks: [], releases: [], artists: [], playlists: [] })
const genres = ref([])
const loading = ref(false)
const chip = ref('all') // all | tracks | artists | albums | playlists

const CHIPS = [
  { id: 'all', label: 'Все' },
  { id: 'tracks', label: 'Треки' },
  { id: 'artists', label: 'Исполнители' },
  { id: 'albums', label: 'Альбомы' },
  { id: 'playlists', label: 'Плейлисты' },
]

const TILE_COLORS = ['#e13300', '#1e3264', '#8d67ab', '#e8115b', '#148a08', '#503750', '#477d95', '#ba5d07', '#0d73ec', '#af2896']

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

watch(
  () => route.params.q,
  (v) => {
    q.value = v || ''
    mq.value = v || ''
    chip.value = 'all'
    runSearch()
  }
)
if (q.value) runSearch()

onMounted(async () => {
  try {
    const { data } = await api.get('/genres')
    genres.value = data.data
  } catch {
    genres.value = []
  }
})

// «Топ-результат» — исполнитель при точном попадании, иначе первый трек.
const topResult = computed(() => {
  const query = q.value.trim().toLowerCase()
  const a = results.value.artists[0]
  if (a && a.name.toLowerCase().includes(query)) return { type: 'artist', item: a }
  const t = results.value.tracks[0]
  if (t) return { type: 'track', item: t }
  if (a) return { type: 'artist', item: a }
  const r = results.value.releases[0]
  if (r) return { type: 'release', item: r }
  return null
})

const empty = computed(
  () =>
    q.value &&
    !loading.value &&
    !results.value.tracks.length &&
    !results.value.artists.length &&
    !results.value.releases.length &&
    !results.value.playlists.length
)

function show(section) {
  return chip.value === 'all' || chip.value === section
}

function playTop() {
  const tr = topResult.value
  if (!tr) return
  if (tr.type === 'track') player.playTrack(tr.item, results.value.tracks, { name: 'Поиск' })
  else if (tr.type === 'artist') playArtist(tr.item)
  else playRelease(tr.item)
}
async function playArtist(a) {
  const { data } = await api.get(`/artists/${a.slug}/top-tracks`)
  if (data.data.length) player.playContext(data.data, 0, { name: a.name })
}
async function playRelease(r) {
  const { data } = await api.get(`/releases/${r.slug}`)
  if (data.data.tracks?.length) player.playContext(data.data.tracks, 0, { name: r.title })
}

function tileColor(i) {
  return TILE_COLORS[i % TILE_COLORS.length]
}
</script>

<template>
  <div class="content-pad search">
    <!-- Мобильный поиск: своя строка, т.к. топбара нет -->
    <div class="search__mbar">
      <h1 class="search__mtitle">Поиск</h1>
      <div class="search__mfield">
        <Icon name="searchSmall" :size="18" />
        <input v-model="mq" placeholder="Что хочешь включить?" @input="onMobileSearch" />
      </div>
    </div>

    <!-- Обзор жанров, когда запрос пуст -->
    <template v-if="!q.trim()">
      <h1 class="section-title" style="font-size: 24px">Все остальное</h1>
      <div class="search__tiles">
        <RouterLink
          v-for="(g, i) in genres"
          :key="g.genre"
          :to="{ name: 'genre', params: { name: g.genre } }"
          class="tile"
          :style="{ background: tileColor(i) }"
        >
          <span class="tile__name">{{ g.genre }}</span>
          <Icon name="album" :size="72" class="tile__art" />
        </RouterLink>
        <RouterLink :to="{ name: 'section', params: { key: 'new' } }" class="tile" :style="{ background: tileColor(genres.length) }">
          <span class="tile__name">Новые релизы</span>
          <Icon name="nowplaying" :size="72" class="tile__art" />
        </RouterLink>
        <RouterLink :to="{ name: 'section', params: { key: 'popular' } }" class="tile" :style="{ background: tileColor(genres.length + 1) }">
          <span class="tile__name">Для тебя</span>
          <Icon name="heartFill" :size="72" class="tile__art" />
        </RouterLink>
      </div>
    </template>

    <!-- Результаты -->
    <template v-else>
      <div class="search__chips">
        <button
          v-for="c in CHIPS"
          :key="c.id"
          class="chip"
          :class="{ 'chip--active': chip === c.id }"
          @click="chip = c.id"
        >
          {{ c.label }}
        </button>
      </div>

      <div v-if="topResult && chip === 'all'" class="search__toprow">
        <section class="search__top">
          <h2 class="section-title">Топ-результат</h2>
          <div class="topcard" @click="playTop">
            <template v-if="topResult.type === 'artist'">
              <img v-if="topResult.item.avatar_url" :src="topResult.item.avatar_url" class="topcard__img topcard__img--round" alt="" />
              <div v-else class="topcard__img topcard__img--round topcard__ph"><Icon name="person" :size="40" /></div>
              <div class="topcard__name">{{ topResult.item.name }}</div>
              <div class="topcard__kind">Исполнитель</div>
            </template>
            <template v-else-if="topResult.type === 'track'">
              <img v-if="topResult.item.cover?.[300] || topResult.item.cover?.[0]?.url" :src="topResult.item.cover?.[300] || topResult.item.cover?.[0]?.url" class="topcard__img" alt="" />
              <div v-else class="topcard__img topcard__ph"><Icon name="album" :size="40" /></div>
              <div class="topcard__name">{{ topResult.item.title }}</div>
              <div class="topcard__kind">Трек • {{ (topResult.item.artists || []).map((a) => a.name).join(', ') }}</div>
            </template>
            <template v-else>
              <div class="topcard__name">{{ topResult.item.title }}</div>
              <div class="topcard__kind">Альбом</div>
            </template>
            <button class="play-btn topcard__play"><Icon name="playBig" :size="20" /></button>
          </div>
        </section>

        <section v-if="results.tracks.length" class="search__tracks">
          <h2 class="section-title">Треки</h2>
          <TrackRow
            v-for="(t, i) in results.tracks.slice(0, 4)"
            :key="t.id"
            :track="t"
            :index="i"
            variant="search"
            :context-tracks="results.tracks"
            context-name="Поиск"
          />
        </section>
      </div>

      <div v-if="chip === 'tracks' && results.tracks.length" class="search__section">
        <TrackRow
          v-for="(t, i) in results.tracks"
          :key="t.id"
          :track="t"
          :index="i"
          variant="search"
          :context-tracks="results.tracks"
          context-name="Поиск"
        />
      </div>

      <div v-if="show('artists') && results.artists.length && chip !== 'all'" class="search__section">
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
      <div v-else-if="chip === 'all' && results.artists.length" class="search__section">
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

      <div v-if="show('albums') && results.releases.length" class="search__section">
        <h2 v-if="chip === 'all'" class="section-title">Альбомы</h2>
        <div class="grid-cards">
          <MediaCard
            v-for="r in results.releases"
            :key="r.id"
            :to="{ name: 'release', params: { slug: r.slug } }"
            :cover="r.cover"
            :title="r.title"
            :subtitle="`${r.year || ''} · ${r.artist?.name || ''}`"
            @play="playRelease(r)"
          />
        </div>
      </div>

      <div v-if="show('playlists') && results.playlists.length" class="search__section">
        <h2 v-if="chip === 'all'" class="section-title">Плейлисты</h2>
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

      <p v-if="empty" class="muted">По запросу «{{ q }}» ничего не найдено.</p>
    </template>
  </div>
</template>

<style scoped>
/* Мобильная строка поиска — скрыта на десктопе. */
.search__mbar {
  display: none;
}
@media (max-width: 768px) {
  .search__mbar {
    display: block;
    margin-bottom: 16px;
  }
  .search__mtitle {
    font-size: 24px;
    font-weight: 700;
    margin-bottom: 12px;
  }
  .search__mfield {
    display: flex;
    align-items: center;
    gap: 10px;
    background: #fff;
    color: #000;
    border-radius: 6px;
    padding: 0 12px;
    height: 46px;
  }
  .search__mfield input {
    flex: 1;
    min-width: 0;
    border: none;
    outline: none;
    background: none;
    color: #000;
    font-size: 16px;
    font-weight: 500;
  }
  .search__mfield input::placeholder {
    color: #555;
  }
}

.search__tiles {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 20px;
}
.tile {
  position: relative;
  border-radius: 8px;
  aspect-ratio: 16 / 9;
  padding: 16px;
  overflow: hidden;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: -0.02em;
  transition: transform 0.12s ease;
}
.tile:hover {
  transform: scale(1.02);
}
.tile__art {
  position: absolute;
  right: -14px;
  bottom: -10px;
  transform: rotate(25deg);
  opacity: 0.5;
  color: #000;
}
.search__chips {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}
.chip {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  border-radius: 999px;
  padding: 7px 14px;
  font-size: 14px;
  font-weight: 500;
}
.chip:hover {
  background: rgba(255, 255, 255, 0.2);
}
.chip--active {
  background: #fff;
  color: #000;
}
.search__toprow {
  display: grid;
  grid-template-columns: minmax(280px, 420px) minmax(0, 1fr);
  gap: 24px;
  margin-bottom: 28px;
}
@media (max-width: 1100px) {
  .search__toprow {
    grid-template-columns: 1fr;
  }
}
.topcard {
  position: relative;
  background: var(--bg-card);
  border-radius: 8px;
  padding: 20px;
  cursor: pointer;
  transition: background 0.2s ease;
  min-height: 200px;
}
.topcard:hover {
  background: var(--bg-card-hover);
}
.topcard__img {
  width: 92px;
  height: 92px;
  border-radius: 6px;
  object-fit: cover;
  margin-bottom: 16px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
}
.topcard__img--round {
  border-radius: 50%;
}
.topcard__ph {
  display: grid;
  place-items: center;
  background: #333;
  color: var(--text-subdued);
}
.topcard__name {
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.02em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.topcard__kind {
  color: var(--text-subdued);
  font-size: 14px;
  margin-top: 6px;
}
.topcard__play {
  position: absolute;
  right: 20px;
  bottom: 20px;
  opacity: 0;
  transform: translateY(8px);
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.topcard:hover .topcard__play {
  opacity: 1;
  transform: translateY(0);
}
.search__section {
  margin-bottom: 28px;
}
</style>
