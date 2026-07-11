<script setup>
import { ref, onMounted, computed } from 'vue'
import api from '@/lib/api'
import Icon from '@/components/Icon.vue'
import CoverImage from '@/components/CoverImage.vue'
import MediaCard from '@/components/MediaCard.vue'
import { usePlayerStore } from '@/stores/player'
import { useLibraryStore } from '@/stores/library'
import { useAuthStore } from '@/stores/auth'
import { useMenuStore } from '@/stores/menu'

const player = usePlayerStore()
const library = useLibraryStore()
const auth = useAuthStore()
const menu = useMenuStore()

const recentlyPlayed = ref([])
const popular = ref([])
const newReleases = ref([])
const mixes = ref([])
const loading = ref(true)

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
  if (data.data.tracks?.length) player.playContext(data.data.tracks, 0, { name: r.title })
}
async function playPlaylist(p) {
  const { data } = await api.get(`/playlists/${p.id}`)
  if (data.data.tracks?.length) player.playContext(data.data.tracks, 0, { name: p.title })
}
async function playLiked() {
  const { data } = await api.get('/library/liked-tracks')
  if (data.data.length) player.playContext(data.data, 0, { name: 'Любимые треки' })
}

// The 2x4 shortcut grid: Liked Songs, then playlists, padded with albums.
const shortcuts = computed(() => {
  const tiles = []
  if (auth.isAuthenticated) {
    tiles.push({ key: 'liked', to: '/liked', title: 'Любимые треки', liked: true })
  }
  for (const p of library.playlists) {
    tiles.push({
      key: `pl-${p.id}`,
      to: { name: 'playlist', params: { id: p.id } },
      title: p.title,
      cover: p.cover_url ? { 300: p.cover_url } : null,
      playlist: p,
    })
  }
  for (const r of newReleases.value) {
    if (tiles.length >= 8) break
    tiles.push({
      key: `rel-${r.id}`,
      to: { name: 'release', params: { slug: r.slug } },
      title: r.title,
      cover: r.cover,
      release: r,
    })
  }
  return tiles.slice(0, 8)
})

function playShortcut(s) {
  if (s.liked) return playLiked()
  if (s.playlist) return playPlaylist(s.playlist)
  if (s.release) return playRelease(s.release)
}

function openShortcutMenu(e, s) {
  if (s.liked) return menu.openEntityMenu(e, { type: 'liked', title: 'Любимые треки' })
  if (s.playlist) {
    return menu.openEntityMenu(e, {
      type: 'playlist', id: s.playlist.id, title: s.title,
      pinType: 'playlist', pinId: s.playlist.id,
    })
  }
  if (s.release) return menu.openEntityMenu(e, { type: 'release', slug: s.release.slug, title: s.title })
}

onMounted(async () => {
  try {
    const { data } = await api.get('/home')
    recentlyPlayed.value = data.recently_played
    popular.value = data.popular_tracks
    newReleases.value = data.new_releases
  } finally {
    loading.value = false
  }
  if (auth.isAuthenticated) {
    api.get('/mixes/daily').then(({ data }) => (mixes.value = data.data)).catch(() => {})
  }
})

async function playMix(m) {
  const { data } = await api.get(`/mixes/daily/${m.n}`)
  if (data.tracks.length) player.playContext(data.tracks, 0, { name: m.title })
}
</script>

<template>
  <div class="home">
    <div class="home__tint"></div>

    <div class="home__content">
      <div class="home__chips">
        <button class="chip chip--active">Все</button>
        <button class="chip">Музыка</button>
      </div>

      <div v-if="shortcuts.length" class="home__shortcuts">
        <RouterLink v-for="s in shortcuts" :key="s.key" :to="s.to" class="shortcut" @contextmenu.prevent="openShortcutMenu($event, s)">
          <div v-if="s.liked" class="shortcut__cover shortcut__cover--liked">
            <Icon name="heartFill" :size="22" />
          </div>
          <CoverImage v-else :cover="s.cover" :size="64" class="shortcut__cover" />
          <span class="shortcut__title">{{ s.title }}</span>
          <button class="play-btn shortcut__play" @click.prevent="playShortcut(s)">
            <Icon name="playBig" :size="20" />
          </button>
        </RouterLink>
      </div>

      <section v-if="mixes.length" class="home__section">
        <div class="shelf__head">
          <h2 class="section-title">Создано для тебя</h2>
        </div>
        <div class="grid-cards">
          <RouterLink
            v-for="m in mixes"
            :key="m.n"
            :to="{ name: 'mix', params: { n: m.n } }"
            class="mixcard"
          >
            <div class="mixcard__art" :style="{ background: m.color }">
              <Icon name="nowplaying" :size="40" />
              <span class="mixcard__tag">{{ m.genre }}</span>
              <button class="play-btn mixcard__play" @click.prevent="playMix(m)">
                <Icon name="playBig" :size="20" />
              </button>
            </div>
            <div class="mixcard__title">{{ m.title }}</div>
            <div class="mixcard__sub">{{ m.artists.join(', ') }} и не только</div>
          </RouterLink>
        </div>
      </section>

      <section v-if="recentlyPlayed.length" class="home__section">
        <div class="shelf__head">
          <h2 class="section-title">Ты недавно слушал(-а)</h2>
          <RouterLink :to="{ name: 'section', params: { key: 'recent' } }" class="shelf__all">Показать все</RouterLink>
        </div>
        <div class="grid-cards">
          <MediaCard
            v-for="t in recentlyPlayed.slice(0, 8)"
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

      <section v-if="popular.length" class="home__section">
        <div class="shelf__head">
          <div>
            <div class="shelf__eyebrow">Только для тебя{{ auth.user ? ',' : '' }}</div>
            <h2 class="section-title">{{ auth.user?.name || 'Популярно сейчас' }}</h2>
          </div>
          <RouterLink :to="{ name: 'section', params: { key: 'popular' } }" class="shelf__all">Показать все</RouterLink>
        </div>
        <div class="grid-cards">
          <MediaCard
            v-for="t in popular.slice(0, 8)"
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

      <section v-if="newReleases.length" class="home__section">
        <div class="shelf__head">
          <h2 class="section-title">Новые релизы</h2>
          <RouterLink :to="{ name: 'section', params: { key: 'new' } }" class="shelf__all">Показать все</RouterLink>
        </div>
        <div class="grid-cards">
          <MediaCard
            v-for="r in newReleases.slice(0, 8)"
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
  </div>
</template>

<style scoped>
.home {
  position: relative;
}
.home__tint {
  position: absolute;
  inset: 0 0 auto 0;
  height: 330px;
  background: linear-gradient(180deg, rgba(83, 71, 132, 0.85) 0%, rgba(43, 38, 66, 0.45) 55%, transparent 100%);
  pointer-events: none;
}
.home__content {
  position: relative;
  padding: 16px 24px 32px;
}
.home__chips {
  display: flex;
  gap: 8px;
  margin-bottom: 20px;
}
.chip {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  border-radius: 999px;
  padding: 7px 14px;
  font-size: 13px;
  font-weight: 500;
}
.chip:hover {
  background: rgba(255, 255, 255, 0.2);
}
.chip--active {
  background: #fff;
  color: #000;
}
.home__shortcuts {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px 10px;
  margin-bottom: 32px;
}
@media (max-width: 1100px) {
  .home__shortcuts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.shortcut {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 4px;
  overflow: hidden;
  height: 56px;
  padding-right: 44px;
}
.shortcut:hover {
  background: rgba(255, 255, 255, 0.2);
}
.shortcut__cover {
  width: 56px;
  height: 56px;
  flex: 0 0 56px;
  border-radius: 0;
  box-shadow: 2px 0 8px rgba(0, 0, 0, 0.25);
}
.shortcut__cover--liked {
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #450af5, #8e8ee5);
  color: #fff;
}
.shortcut__title {
  font-weight: 700;
  font-size: 14px;
  min-width: 0;
  overflow: hidden;
  overflow-wrap: anywhere;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.shortcut__play {
  position: absolute;
  right: 8px;
  top: 50%;
  translate: 0 -50%;
  width: 40px;
  height: 40px;
  opacity: 0;
  transition: opacity 0.2s ease;
}
.shortcut:hover .shortcut__play {
  opacity: 1;
}
.home__section {
  margin-bottom: 28px;
}
.shelf__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 4px;
}
.shelf__eyebrow {
  color: var(--text-subdued);
  font-size: 13px;
  font-weight: 500;
}
.shelf__all {
  color: var(--text-subdued);
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  padding-bottom: 18px;
}
.shelf__all:hover {
  text-decoration: underline;
}
.grid-cards {
  margin: 0 -12px;
}
.mixcard {
  display: block;
  padding: 12px;
  border-radius: 6px;
}
.mixcard:hover {
  background: rgba(255, 255, 255, 0.07);
}
.mixcard__art {
  position: relative;
  aspect-ratio: 1;
  border-radius: 6px;
  display: grid;
  place-items: center;
  color: rgba(255, 255, 255, 0.9);
  margin-bottom: 10px;
  overflow: hidden;
}
.mixcard__tag {
  position: absolute;
  left: 10px;
  bottom: 8px;
  background: rgba(0, 0, 0, 0.55);
  border-radius: 4px;
  padding: 3px 8px;
  font-size: 12px;
  font-weight: 700;
}
.mixcard__play {
  position: absolute;
  right: 8px;
  bottom: 8px;
  opacity: 0;
  transform: translateY(8px);
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.mixcard:hover .mixcard__play {
  opacity: 1;
  transform: translateY(0);
}
.mixcard__title {
  font-weight: 700;
  font-size: 15px;
}
.mixcard__sub {
  color: var(--text-subdued);
  font-size: 13px;
  margin-top: 4px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
