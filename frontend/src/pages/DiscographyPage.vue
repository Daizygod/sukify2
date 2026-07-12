<script setup>
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import api from '@/lib/api'
import Icon from '@/components/Icon.vue'
import MediaCard from '@/components/MediaCard.vue'
import CoverImage from '@/components/CoverImage.vue'
import { usePlayerStore } from '@/stores/player'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()

const artist = ref(null)

// Фильтр по типу релиза и сортировка — как на странице дискографии в Spotify.
const filter = ref('all') // all | albums | singles
const sort = ref('date') // date | name
const layout = ref('grid') // grid | list
const sortOpen = ref(false)

const typeLabel = {
  album: 'Альбом',
  single: 'Сингл',
  ep: 'Мини-альбом',
  compilation: 'Сборник',
}

async function load(slug) {
  const { data } = await api.get(`/artists/${slug}`)
  artist.value = data.data
  filter.value = 'all'
}
watch(() => route.params.slug, (s) => s && load(s), { immediate: true })

const filters = computed(() => {
  const rs = artist.value?.releases || []
  const tabs = [{ value: 'all', label: 'Все' }]
  if (rs.some((r) => r.type === 'album' || r.type === 'compilation')) tabs.push({ value: 'albums', label: 'Альбомы' })
  if (rs.some((r) => r.type === 'single' || r.type === 'ep')) tabs.push({ value: 'singles', label: 'Синглы и EP' })
  return tabs
})

const releases = computed(() => {
  let rs = [...(artist.value?.releases || [])]
  if (filter.value === 'albums') rs = rs.filter((r) => r.type === 'album' || r.type === 'compilation')
  if (filter.value === 'singles') rs = rs.filter((r) => r.type === 'single' || r.type === 'ep')
  if (sort.value === 'name') {
    rs.sort((a, b) => a.title.localeCompare(b.title, 'ru'))
  } else {
    rs.sort((a, b) => (b.release_date || '').localeCompare(a.release_date || ''))
  }
  return rs
})

const sortLabel = computed(() => (sort.value === 'name' ? 'По алфавиту' : 'Дата выпуска'))

function pickSort(value) {
  sort.value = value
  sortOpen.value = false
}

async function playRelease(r) {
  if (player.currentTrack?.release?.slug === r.slug && player.isPlaying) return player.togglePlay()
  const { data } = await api.get(`/releases/${r.slug}`)
  if (data.data.tracks?.length) player.playContext(data.data.tracks, 0, { name: r.title })
}

function isReleasePlaying(r) {
  return player.currentTrack?.release?.slug === r.slug && player.isPlaying
}
function openRelease(r) {
  router.push({ name: 'release', params: { slug: r.slug } })
}
</script>

<template>
  <div v-if="artist" class="disco content-pad">
    <div class="disco__head">
      <RouterLink :to="{ name: 'artist', params: { slug: artist.slug } }" class="disco__artist">{{ artist.name }}</RouterLink>
      <h1 class="disco__title">Дискография</h1>
    </div>

    <div class="disco__bar">
      <div class="disco__chips">
        <button
          v-for="f in filters"
          :key="f.value"
          class="disco__chip"
          :class="{ on: filter === f.value }"
          @click="filter = f.value"
        >{{ f.label }}</button>
      </div>

      <div class="disco__tools">
        <div class="disco__sort">
          <button class="disco__sortbtn" @click="sortOpen = !sortOpen">
            <span class="disco__sortby">{{ sortLabel }}</span>
            <Icon name="caretDown" :size="16" />
          </button>
          <div v-if="sortOpen" class="disco__menu" @mouseleave="sortOpen = false">
            <div class="disco__menuhead">Сортировка</div>
            <button class="disco__menuitem" :class="{ on: sort === 'date' }" @click="pickSort('date')">
              Дата выпуска <Icon v-if="sort === 'date'" name="check" :size="16" />
            </button>
            <button class="disco__menuitem" :class="{ on: sort === 'name' }" @click="pickSort('name')">
              По алфавиту <Icon v-if="sort === 'name'" name="check" :size="16" />
            </button>
          </div>
        </div>
        <button
          class="disco__layout"
          :title="layout === 'grid' ? 'Показать списком' : 'Показать сеткой'"
          @click="layout = layout === 'grid' ? 'list' : 'grid'"
        >
          <Icon :name="layout === 'grid' ? 'list' : 'grid'" :size="18" />
        </button>
      </div>
    </div>

    <!-- Сетка -->
    <div v-if="layout === 'grid'" class="grid-cards">
      <MediaCard
        v-for="r in releases"
        :key="r.id"
        :to="{ name: 'release', params: { slug: r.slug } }"
        :cover="r.cover"
        :title="r.title"
        :subtitle="`${r.year || ''} • ${typeLabel[r.type] || r.type}`"
        :playing="isReleasePlaying(r)"
        @play="playRelease(r)"
      />
    </div>

    <!-- Список -->
    <div v-else class="disco__list">
      <div v-for="r in releases" :key="r.id" class="disco__row" @click="openRelease(r)">
        <div class="disco__rowart">
          <CoverImage :cover="r.cover" :size="80" />
          <button class="play-btn disco__rowplay" :class="{ 'disco__rowplay--on': isReleasePlaying(r) }" @click.stop="playRelease(r)">
            <Icon :name="isReleasePlaying(r) ? 'pauseBig' : 'playBig'" :size="20" />
          </button>
        </div>
        <div class="disco__rowmeta">
          <div class="disco__rowtitle">{{ r.title }}</div>
          <div class="disco__rowsub">{{ r.year || '' }} • {{ typeLabel[r.type] || r.type }}</div>
        </div>
      </div>
    </div>

    <p v-if="!releases.length" class="muted">Здесь пока пусто.</p>
  </div>
</template>

<style scoped>
.disco__head {
  margin-bottom: 24px;
}
.disco__artist {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-subdued);
}
.disco__artist:hover {
  text-decoration: underline;
  color: #fff;
}
.disco__title {
  font-size: clamp(32px, 5vw, 48px);
  font-weight: 800;
  margin-top: 4px;
}
.disco__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
  flex-wrap: wrap;
}
.disco__chips {
  display: flex;
  gap: 8px;
}
.disco__chip {
  font-size: 14px;
  color: #fff;
  background: rgba(255, 255, 255, 0.07);
  border-radius: 999px;
  padding: 8px 16px;
  line-height: 1;
}
.disco__chip:hover {
  background: rgba(255, 255, 255, 0.12);
}
.disco__chip.on {
  background: #fff;
  color: #000;
}
.disco__tools {
  display: flex;
  align-items: center;
  gap: 8px;
}
.disco__sort {
  position: relative;
}
.disco__sortbtn {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-subdued);
  font-size: 14px;
  font-weight: 700;
  padding: 6px 8px;
}
.disco__sortbtn:hover {
  color: #fff;
}
.disco__menu {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  z-index: 20;
  min-width: 200px;
  background: #282828;
  border-radius: 6px;
  padding: 4px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5);
}
.disco__menuhead {
  color: var(--text-subdued);
  font-size: 12px;
  padding: 8px 12px 6px;
}
.disco__menuitem {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  text-align: left;
  color: #fff;
  font-size: 14px;
  padding: 10px 12px;
  border-radius: 4px;
}
.disco__menuitem:hover {
  background: rgba(255, 255, 255, 0.1);
}
.disco__menuitem.on {
  color: var(--accent);
}
.disco__layout {
  color: var(--text-subdued);
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 50%;
}
.disco__layout:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
/* Список */
.disco__list {
  display: flex;
  flex-direction: column;
}
.disco__row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px;
  border-radius: 6px;
}
.disco__row:hover {
  background: rgba(255, 255, 255, 0.08);
}
.disco__rowart {
  position: relative;
  width: 64px;
  height: 64px;
  flex: 0 0 64px;
}
.disco__rowplay {
  position: absolute;
  right: 4px;
  bottom: 4px;
  width: 36px;
  height: 36px;
  opacity: 0;
  transform: translateY(6px);
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.disco__row:hover .disco__rowplay,
.disco__rowplay--on {
  opacity: 1;
  transform: none;
}
.disco__rowtitle {
  font-size: 16px;
  font-weight: 600;
}
.disco__rowsub {
  color: var(--text-subdued);
  font-size: 14px;
  margin-top: 2px;
}
</style>
