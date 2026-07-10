<script setup>
import { ref, computed, nextTick } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import { useAuthStore } from '@/stores/auth'
import { useLibraryStore } from '@/stores/library'
import { trackCount } from '@/lib/format'

const auth = useAuthStore()
const library = useLibraryStore()
const route = useRoute()
const filter = ref('')
const searchOpen = ref(false)
const filterEl = ref(null)

const filteredPlaylists = computed(() => {
  const q = filter.value.trim().toLowerCase()
  const list = library.playlists
  return q ? list.filter((p) => p.title.toLowerCase().includes(q)) : list
})

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
            <Icon name="fullscreen" :size="16" />
          </button>
        </div>
      </div>

      <template v-if="auth.isAuthenticated">
        <div class="sidebar__pills">
          <button class="pill pill--active">Плейлисты</button>
          <button class="pill">Исполнители</button>
          <button class="pill">Альбомы</button>
        </div>

        <div class="sidebar__tools">
          <div class="sidebar__searchwrap" :class="{ open: searchOpen }">
            <button class="sidebar__searchbtn" title="Поиск в медиатеке" @click="openSearch">
              <Icon name="search" :size="16" />
            </button>
            <input
              v-if="searchOpen"
              ref="filterEl"
              v-model="filter"
              class="sidebar__filter"
              placeholder="Поиск в медиатеке"
            />
          </div>
          <button class="sidebar__sort">
            <span>Недавние</span>
            <Icon name="list" :size="16" />
          </button>
        </div>

        <div class="sidebar__list">
          <RouterLink to="/liked" class="libitem libitem--liked" :class="{ active: route.name === 'liked' }">
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
            v-for="p in filteredPlaylists"
            :key="p.id"
            :to="{ name: 'playlist', params: { id: p.id } }"
            class="libitem"
            :class="{ active: route.name === 'playlist' && String(route.params.id) === String(p.id) }"
          >
            <CoverImage :cover="p.cover_url ? { 300: p.cover_url } : null" :size="48" class="libitem__cover" />
            <div class="libitem__meta">
              <div class="libitem__title">{{ p.title }}</div>
              <div class="libitem__sub"><span>Плейлист • {{ p.owner?.name }}</span></div>
            </div>
          </RouterLink>
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
  gap: 8px;
  padding: 4px 8px 8px 4px;
  flex-wrap: wrap;
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
.sidebar__sort {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-subdued);
  font-size: 13px;
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
