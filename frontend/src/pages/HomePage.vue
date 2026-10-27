<script setup>
import { ref, onMounted, computed } from 'vue'
import api from '@/lib/api'
import Icon from '@/components/Icon.vue'
import CoverImage from '@/components/CoverImage.vue'
import MediaCard from '@/components/MediaCard.vue'
import PlayButton from '@/components/PlayButton.vue'
import ShelfRow from '@/components/ShelfRow.vue'
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
const recentlyAdded = ref([])
const mixes = ref([])
const loading = ref(true)

function isTrackPlaying(t) {
  return player.currentTrack?.id === t.id && player.isPlaying
}

function playTrackCard(t, list) {
  if (player.currentTrack?.id === t.id) return player.togglePlay()
  player.playTrack(t, list)
}
/** Загрузчики треков для самодостаточных кнопок ▶ (грузим по клику). */
async function releaseTracks(r) {
  const { data } = await api.get(`/releases/${r.slug}`)
  return data.data.tracks || []
}
async function playlistTracks(p) {
  const { data } = await api.get(`/playlists/${p.id}`)
  return data.data.tracks || []
}
async function likedTracks() {
  const { data } = await api.get('/library/liked-tracks')
  return data.data || []
}
async function mixTracks(m) {
  const { data } = await api.get(`/mixes/daily/${m.n}`)
  return data.tracks || []
}

/** Ключ и загрузчик для плитки быстрого доступа. */
function shortcutContext(s) {
  if (s.liked) return { key: 'liked', tracks: likedTracks, name: 'Любимые треки' }
  if (s.playlist) return { key: `playlist:${s.playlist.id}`, tracks: () => playlistTracks(s.playlist), name: s.title }
  return { key: `release:${s.release.slug}`, tracks: () => releaseTracks(s.release), name: s.title }
}

// The 2x4 shortcut grid: Liked Songs, then playlists, padded with albums.
const shortcuts = computed(() => {
  const tiles = []
  if (auth.isAuthenticated) {
    tiles.push({ key: 'liked', to: '/liked', title: 'Любимые треки', liked: true, color: '#5039ab' })
  }
  for (const p of library.playlists) {
    tiles.push({
      key: `pl-${p.id}`,
      to: { name: 'playlist', params: { id: p.id } },
      title: p.title,
      cover: p.cover_url ? { 300: p.cover_url } : null,
      playlist: p,
      color: p.colors?.background || '#4b4b5c',
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
      color: r.colors?.background || '#4b4b5c',
    })
  }
  return tiles.slice(0, 8)
})

// Шапка главной как у Spotify: цвет первой плитки, а при наведении — цвет
// той, что под курсором; перетекает за секунду.
const hoverColor = ref(null)
const tintColor = computed(() => hoverColor.value || shortcuts.value[0]?.color || '#3e3a5c')

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
    recentlyAdded.value = data.recently_added || []
  } finally {
    loading.value = false
  }
  if (auth.isAuthenticated) {
    api.get('/mixes/daily').then(({ data }) => (mixes.value = data.data)).catch(() => {})
  }
})

</script>

<template>
  <div class="home">
    <div class="home__tint" :style="{ backgroundColor: tintColor }"></div>

    <div class="home__content">
      <div class="home__chips">
        <div class="home__ava">
          <img v-if="auth.user?.avatar_url" :src="auth.user.avatar_url" alt="" />
          <span v-else>{{ (auth.user?.name || '?').slice(0, 1).toUpperCase() }}</span>
        </div>
        <button class="chip chip--active">Все</button>
        <button class="chip">Музыка</button>
      </div>

      <div v-if="shortcuts.length" class="home__shortcuts" @mouseleave="hoverColor = null">
        <RouterLink
          v-for="s in shortcuts"
          :key="s.key"
          :to="s.to"
          class="shortcut"
          @mouseenter="hoverColor = s.color"
          @contextmenu.prevent="openShortcutMenu($event, s)"
        >
          <div v-if="s.liked" class="shortcut__cover shortcut__cover--liked">
            <Icon name="heartFill" :size="22" />
          </div>
          <CoverImage v-else :cover="s.cover" :size="64" class="shortcut__cover" />
          <span class="shortcut__title">{{ s.title }}</span>
          <PlayButton
            class="shortcut__play"
            :context-key="shortcutContext(s).key"
            :tracks="shortcutContext(s).tracks"
            :name="shortcutContext(s).name"
            :size="20"
            @click.prevent
          />
        </RouterLink>
      </div>

      <section v-if="mixes.length" class="home__section">
        <div class="shelf__head">
          <h2 class="section-title">Создано для тебя</h2>
        </div>
        <ShelfRow>
          <RouterLink
            v-for="m in mixes"
            :key="m.n"
            :to="{ name: 'mix', params: { n: m.n } }"
            class="mixcard"
          >
            <div class="mixcard__art" :style="{ background: m.color }">
              <Icon name="nowplaying" :size="40" />
              <span class="mixcard__tag">{{ m.genre }}</span>
              <PlayButton
                class="mixcard__play"
                :context-key="`mix:${m.n}`"
                :tracks="() => mixTracks(m)"
                :name="m.title"
                :size="20"
                @click.prevent
              />
            </div>
            <div class="mixcard__title">{{ m.title }}</div>
            <div class="mixcard__sub">{{ m.artists.join(', ') }} и не только</div>
          </RouterLink>
        </ShelfRow>
      </section>

      <section v-if="recentlyPlayed.length" class="home__section">
        <div class="shelf__head">
          <h2 class="section-title">Ты недавно слушал(-а)</h2>
          <RouterLink :to="{ name: 'section', params: { key: 'recent' } }" class="shelf__all">Показать все</RouterLink>
        </div>
        <ShelfRow>
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
        </ShelfRow>
      </section>

      <section v-if="popular.length" class="home__section">
        <div class="shelf__head">
          <div>
            <div class="shelf__eyebrow">Только для тебя{{ auth.user ? ',' : '' }}</div>
            <h2 class="section-title">{{ auth.user?.name || 'Популярно сейчас' }}</h2>
          </div>
          <RouterLink :to="{ name: 'section', params: { key: 'popular' } }" class="shelf__all">Показать все</RouterLink>
        </div>
        <ShelfRow>
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
        </ShelfRow>
      </section>

      <section v-if="newReleases.length" class="home__section">
        <div class="shelf__head">
          <h2 class="section-title">Новые релизы</h2>
          <RouterLink :to="{ name: 'section', params: { key: 'new' } }" class="shelf__all">Показать все</RouterLink>
        </div>
        <ShelfRow>
          <MediaCard
            v-for="r in newReleases.slice(0, 8)"
            :key="r.id"
            :to="{ name: 'release', params: { slug: r.slug } }"
            :cover="r.cover"
            :title="r.title"
            :subtitle="`${r.year || ''} · ${r.artist?.name || ''}`"
            :context-key="`release:${r.slug}`"
            :tracks="() => releaseTracks(r)"
            :context-name="r.title"
          />
        </ShelfRow>
      </section>

      <section v-if="recentlyAdded.length" class="home__section">
        <div class="shelf__head">
          <h2 class="section-title">Недавно в Sukify</h2>
          <RouterLink :to="{ name: 'releases' }" class="shelf__all">Показать все</RouterLink>
        </div>
        <ShelfRow>
          <MediaCard
            v-for="r in recentlyAdded.slice(0, 8)"
            :key="r.id"
            :to="{ name: 'release', params: { slug: r.slug } }"
            :cover="r.cover"
            :title="r.title"
            :subtitle="`${r.year || ''} · ${r.artist?.name || ''}`"
            :context-key="`release:${r.slug}`"
            :tracks="() => releaseTracks(r)"
            :context-name="r.title"
          />
        </ShelfRow>
      </section>
    </div>
  </div>
</template>

<style scoped>
.home {
  position: relative;
}
/* Цветная шапка главной — ровно как у Spotify: цвет плитки, поверх
   затемнение к #121212 и зерно; цвет меняется с переливом за 1 с. */
.home__tint {
  position: absolute;
  inset: 0 0 auto 0;
  height: 332px;
  background-color: #3e3a5c;
  background-image: var(--grain), linear-gradient(180deg, rgba(0, 0, 0, 0.45) 0%, #121212 100%);
  transition: background-color 1s ease;
  pointer-events: none;
}
.home__content {
  position: relative;
  padding: 16px 24px 32px;
}
.home__chips {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 20px;
}
/* Аватарка в шапке — только на мобильном, как в приложении. */
.home__ava {
  display: none;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  overflow: hidden;
  background: #509bf5;
  color: #000;
  font-weight: 700;
  font-size: 15px;
  place-items: center;
  flex: 0 0 34px;
}
.home__ava img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
@media (max-width: 768px) {
  .home__ava {
    display: grid;
  }
  .home__content {
    padding: 12px 16px 24px;
  }
  .home__shortcuts {
    gap: 8px;
    margin-bottom: 24px;
  }
  .shortcut__play {
    display: none;
  }
  .shelf__all {
    display: none;
  }
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
  padding-right: 10px;
  transition: background-color 0.25s ease;
  animation: shortcut-in 0.45s var(--ease-spotify) backwards;
}
.shortcut:nth-child(2) { animation-delay: 30ms; }
.shortcut:nth-child(3) { animation-delay: 60ms; }
.shortcut:nth-child(4) { animation-delay: 90ms; }
.shortcut:nth-child(5) { animation-delay: 120ms; }
.shortcut:nth-child(6) { animation-delay: 150ms; }
.shortcut:nth-child(7) { animation-delay: 180ms; }
.shortcut:nth-child(8) { animation-delay: 210ms; }
@keyframes shortcut-in {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.98);
  }
}
.shortcut:hover {
  background: rgba(255, 255, 255, 0.2);
}
.shortcut:active {
  background: rgba(255, 255, 255, 0.14);
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
/* Переносим по словам (раньше рвалось посреди слова: «Любим|ые»); под
   кнопкой ▶ хвост названия растворяется, а не обрезается. */
.shortcut__title {
  font-weight: 700;
  font-size: 14px;
  line-height: 1.25;
  min-width: 0;
  overflow: hidden;
  overflow-wrap: break-word;
  hyphens: auto;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.shortcut:hover .shortcut__title {
  -webkit-mask-image: linear-gradient(90deg, #000 calc(100% - 56px), transparent calc(100% - 36px));
  mask-image: linear-gradient(90deg, #000 calc(100% - 56px), transparent calc(100% - 36px));
}
.shortcut__play {
  position: absolute;
  right: 8px;
  top: 50%;
  translate: 0 -50%;
  width: 40px;
  height: 40px;
  opacity: 0;
  transform: scale(0.9);
  transition: opacity 0.2s ease, transform 0.2s var(--ease-spotify);
}
.shortcut:hover .shortcut__play {
  opacity: 1;
  transform: none;
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
  font-size: 14px;
  font-weight: 500;
}
.shelf__all {
  color: var(--text-subdued);
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  padding-bottom: 18px;
}
.shelf__all:hover {
  text-decoration: underline;
}
.mixcard {
  display: block;
  padding: 12px;
  border-radius: 6px;
  transition: background-color 0.25s ease;
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
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5);
}
/* Обложка микса — не плоская заливка: блик сверху-слева, глубина снизу, зерно. */
.mixcard__art::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: var(--grain), radial-gradient(120% 90% at 20% 10%, rgba(255, 255, 255, 0.28), transparent 55%),
    linear-gradient(180deg, transparent 40%, rgba(0, 0, 0, 0.35));
  pointer-events: none;
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
  font-size: 16px;
}
.mixcard__sub {
  color: var(--text-subdued);
  font-size: 14px;
  margin-top: 4px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
