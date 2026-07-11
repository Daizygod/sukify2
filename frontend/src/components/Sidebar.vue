<script setup>
import { ref, computed, nextTick } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import { useAuthStore } from '@/stores/auth'
import { useLibraryStore } from '@/stores/library'
import { useMenuStore } from '@/stores/menu'
import { trackCount } from '@/lib/format'

const auth = useAuthStore()
const library = useLibraryStore()
const menuStore = useMenuStore()
const route = useRoute()
const filter = ref('')
const searchOpen = ref(false)
const filterEl = ref(null)
const activeTab = ref('') // '' | 'playlists' | 'artists' | 'albums'
const sortMode = ref(localStorage.getItem('lib.sort') || 'recent') // recent | alpha
const sortOpen = ref(false)

const sortLabel = computed(() => (sortMode.value === 'alpha' ? 'По алфавиту' : 'Недавние'))

function pickSort(mode) {
  sortMode.value = mode
  localStorage.setItem('lib.sort', mode)
  sortOpen.value = false
}

const TABS = [
  { id: 'playlists', label: 'Плейлисты' },
  { id: 'artists', label: 'Исполнители' },
  { id: 'albums', label: 'Альбомы' },
]

const releaseKind = {
  album: 'Альбом',
  single: 'Сингл',
  ep: 'Мини-альбом',
  compilation: 'Сборник',
}

// One mixed list like the original: playlists, albums and artists together,
// narrowed by the active pill and the text filter.
const items = computed(() => {
  const out = []
  const tab = activeTab.value

  if (!tab || tab === 'playlists') {
    for (const p of library.playlists) {
      out.push({
        key: `pl-${p.id}`,
        pinType: 'playlist',
        pinId: p.id,
        to: { name: 'playlist', params: { id: p.id } },
        cover: p.cover_url ? { 300: p.cover_url } : null,
        title: p.title,
        sub: `Плейлист • ${p.owner?.name || ''}`,
        active: route.name === 'playlist' && String(route.params.id) === String(p.id),
      })
    }
  }
  if (!tab || tab === 'albums') {
    for (const r of library.likedAlbums) {
      out.push({
        key: `al-${r.id}`,
        pinType: 'album',
        pinId: r.id,
        to: { name: 'release', params: { slug: r.slug } },
        cover: r.cover,
        title: r.title,
        sub: `${releaseKind[r.type] || 'Альбом'} • ${r.artist?.name || ''}`,
        active: route.name === 'release' && route.params.slug === r.slug,
      })
    }
  }
  if (!tab || tab === 'artists') {
    for (const a of library.followedArtists) {
      out.push({
        key: `ar-${a.id}`,
        pinType: 'artist',
        pinId: a.id,
        to: { name: 'artist', params: { slug: a.slug } },
        cover: a.avatar_url ? { 300: a.avatar_url } : null,
        round: true,
        title: a.name,
        sub: 'Исполнитель',
        active: route.name === 'artist' && route.params.slug === a.slug,
      })
    }
  }

  for (const i of out) i.pinned = library.isPinned(i.pinType, i.pinId)

  const q = filter.value.trim().toLowerCase()
  let filtered = q ? out.filter((i) => i.title.toLowerCase().includes(q)) : out
  if (sortMode.value === 'alpha') {
    filtered = [...filtered].sort((a, b) => a.title.localeCompare(b.title, 'ru'))
  }
  // Закреплённые всегда сверху, как в оригинале.
  return [...filtered.filter((i) => i.pinned), ...filtered.filter((i) => !i.pinned)]
})

function openItemMenu(e, item) {
  const type = item.pinType === 'album' ? 'release' : item.pinType
  menuStore.openEntityMenu(e, {
    type,
    id: item.pinId,
    slug: item.to?.params?.slug,
    n: undefined,
    title: item.title,
    pinType: item.pinType,
    pinId: item.pinId,
  })
}

function openLikedMenu(e) {
  menuStore.openEntityMenu(e, { type: 'liked', title: 'Любимые треки' })
}

const showLiked = computed(() => {
  const tabOk = !activeTab.value || activeTab.value === 'playlists'
  const q = filter.value.trim().toLowerCase()
  return tabOk && (!q || 'любимые треки'.includes(q))
})

function pickTab(id) {
  activeTab.value = activeTab.value === id ? '' : id
}

async function openSearch() {
  searchOpen.value = !searchOpen.value
  if (searchOpen.value) {
    await nextTick()
    filterEl.value?.focus()
  } else {
    filter.value = ''
  }
}

async function createPlaylist() {
  await library.createPlaylist('Мой плейлист №' + (library.playlists.length + 1))
}
</script>

<template>
  <nav class="sidebar">
    <div class="sidebar__library">
      <div class="sidebar__libhead">
        <div class="sidebar__libtitle">
          <Icon name="library" :size="24" /> <span>Моя медиатека</span>
        </div>
        <div class="sidebar__headactions">
          <button v-if="auth.isAuthenticated" class="sidebar__create" title="Создать плейлист" @click="createPlaylist">
            <Icon name="plus" :size="14" />
            <span>Создать</span>
          </button>
          <button class="sidebar__expand" title="Развернуть">
            <Icon name="expand" :size="16" />
          </button>
        </div>
      </div>

      <template v-if="auth.isAuthenticated">
        <div class="sidebar__pills">
          <template v-if="activeTab">
            <button class="sidebar__clear" title="Убрать фильтр" @click="activeTab = ''">
              <Icon name="plus" :size="14" style="transform: rotate(45deg)" />
            </button>
            <button class="pill pill--active" @click="activeTab = ''">
              {{ TABS.find((t) => t.id === activeTab)?.label }}
            </button>
          </template>
          <template v-else>
            <button v-for="t in TABS" :key="t.id" class="pill" @click="pickTab(t.id)">
              {{ t.label }}
            </button>
          </template>
        </div>

        <div class="sidebar__tools">
          <div class="sidebar__searchwrap" :class="{ open: searchOpen }">
            <button class="sidebar__searchbtn" title="Поиск в медиатеке" @click="openSearch">
              <Icon name="searchSmall" :size="16" />
            </button>
            <input
              v-if="searchOpen"
              ref="filterEl"
              v-model="filter"
              class="sidebar__filter"
              placeholder="Поиск в медиатеке"
            />
          </div>
          <div class="sidebar__sortwrap">
            <button class="sidebar__sort" @click="sortOpen = !sortOpen">
              <span>{{ sortLabel }}</span>
              <Icon name="list" :size="16" />
            </button>
            <div v-if="sortOpen" class="sidebar__sortmenu" @mouseleave="sortOpen = false">
              <div class="sidebar__sorthead">Сортировка</div>
              <button class="sidebar__sortitem" :class="{ on: sortMode === 'recent' }" @click="pickSort('recent')">Недавние</button>
              <button class="sidebar__sortitem" :class="{ on: sortMode === 'alpha' }" @click="pickSort('alpha')">По алфавиту</button>
            </div>
          </div>
        </div>

        <div class="sidebar__list">
          <RouterLink v-if="showLiked" to="/liked" class="libitem libitem--liked" :class="{ active: route.name === 'liked' }" @contextmenu.prevent="openLikedMenu">
            <div class="libitem__cover libitem__cover--liked">
              <Icon name="heartFill" :size="20" />
            </div>
            <div class="libitem__meta">
              <div class="libitem__title">Любимые треки</div>
              <div class="libitem__sub">
                <Icon name="pin" :size="12" class="libitem__pin" />
                <span>Плейлист • {{ trackCount(library.likedTrackIds.size) }}</span>
              </div>
            </div>
          </RouterLink>

          <RouterLink
            v-for="item in items"
            :key="item.key"
            :to="item.to"
            class="libitem"
            :class="{ active: item.active }"
            @contextmenu.prevent="openItemMenu($event, item)"
          >
            <CoverImage :cover="item.cover" :size="48" class="libitem__cover" :rounded="item.round" />
            <div class="libitem__meta">
              <div class="libitem__title">{{ item.title }}</div>
              <div class="libitem__sub">
                <Icon v-if="item.pinned" name="pin" :size="12" class="libitem__pin" />
                <span>{{ item.sub }}</span>
              </div>
            </div>
          </RouterLink>

          <p v-if="!items.length && filter" class="sidebar__nores">Ничего не найдено.</p>
        </div>
      </template>

      <div v-else class="sidebar__signin">
        <p>Войди, чтобы увидеть свою медиатеку.</p>
        <RouterLink to="/login" class="btn-primary" style="display:inline-block;margin-top:12px">Войти</RouterLink>
      </div>
    </div>
  </nav>
</template>

<style scoped>
.sidebar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
  min-height: 0;
}
.sidebar__library {
  background: var(--bg-elevated);
  border-radius: var(--radius);
  padding: 8px 4px 8px 12px;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.sidebar__libhead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px 8px 4px;
}
.sidebar__libtitle {
  display: flex;
  align-items: center;
  gap: 12px;
  font-weight: 700;
  font-size: 15px;
  color: #fff;
}
.sidebar__headactions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.sidebar__create {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #1f1f1f;
  color: #fff;
  font-weight: 700;
  font-size: 13px;
  border-radius: 999px;
  padding: 8px 14px;
}
.sidebar__create:hover {
  background: #2a2a2a;
  transform: scale(1.03);
}
.sidebar__expand {
  color: var(--text-subdued);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
}
.sidebar__expand:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.sidebar__pills {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 8px 4px;
  flex-wrap: wrap;
}
.sidebar__clear {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #2a2a2a;
  color: var(--text-subdued);
  display: grid;
  place-items: center;
  flex: 0 0 28px;
}
.sidebar__clear:hover {
  background: #333;
  color: #fff;
}
.pill {
  background: #2a2a2a;
  color: #fff;
  border-radius: 999px;
  padding: 6px 12px;
  font-size: 13px;
  white-space: nowrap;
}
.pill:hover {
  background: #333;
}
.pill--active {
  background: #fff;
  color: #000;
}
.sidebar__tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 2px 8px 4px 4px;
}
.sidebar__searchwrap {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
  border-radius: 4px;
}
.sidebar__searchwrap.open {
  background: #2a2a2a;
}
.sidebar__searchbtn {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  color: var(--text-subdued);
  border-radius: 50%;
  flex: 0 0 32px;
}
.sidebar__searchbtn:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.sidebar__filter {
  flex: 1;
  min-width: 0;
  background: none;
  border: none;
  outline: none;
  color: #fff;
  font-size: 13px;
  padding: 6px 8px 6px 0;
}
.sidebar__filter::placeholder {
  color: var(--text-muted);
}
.sidebar__sortwrap {
  position: relative;
}
.sidebar__sort {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-subdued);
  font-size: 13px;
}
.sidebar__sortmenu {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  background: #282828;
  border-radius: 4px;
  min-width: 170px;
  padding: 4px;
  box-shadow: 0 16px 24px rgba(0, 0, 0, 0.4);
  z-index: 40;
}
.sidebar__sorthead {
  color: var(--text-subdued);
  font-size: 11px;
  font-weight: 700;
  padding: 8px 12px 4px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}
.sidebar__sortitem {
  display: block;
  width: 100%;
  text-align: left;
  padding: 10px 12px;
  color: #fff;
  font-size: 14px;
  border-radius: 2px;
}
.sidebar__sortitem:hover {
  background: rgba(255, 255, 255, 0.1);
}
.sidebar__sortitem.on {
  color: var(--accent);
}
.sidebar__sort:hover {
  color: #fff;
  transform: scale(1.02);
}
.sidebar__list {
  overflow-y: auto;
  flex: 1;
  min-height: 0;
  padding-right: 4px;
}
.sidebar__nores {
  color: var(--text-subdued);
  font-size: 13px;
  padding: 16px 8px;
}
.libitem {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px;
  border-radius: 4px;
}
.libitem:hover {
  background: rgba(255, 255, 255, 0.08);
}
.libitem.active {
  background: rgba(255, 255, 255, 0.12);
}
.libitem__cover {
  width: 48px;
  flex: 0 0 48px;
  border-radius: 4px;
}
.libitem__cover--liked {
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #450af5, #8e8ee5);
  color: #fff;
  aspect-ratio: 1;
  box-shadow: none;
}
.libitem__meta {
  min-width: 0;
}
.libitem__title {
  color: #fff;
  font-weight: 500;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.libitem--liked.active .libitem__title,
.libitem.active .libitem__title {
  color: var(--accent);
}
.libitem__sub {
  color: var(--text-subdued);
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: flex;
  align-items: center;
  gap: 6px;
}
.libitem__pin {
  color: var(--accent);
  flex: 0 0 12px;
}
.sidebar__signin {
  padding: 16px;
  color: var(--text-subdued);
  font-size: 14px;
}
</style>
