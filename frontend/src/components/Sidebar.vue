<script setup>
import { ref, computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import { useAuthStore } from '@/stores/auth'
import { useLibraryStore } from '@/stores/library'

const auth = useAuthStore()
const library = useLibraryStore()
const route = useRoute()
const filter = ref('')

const filteredPlaylists = computed(() => {
  const q = filter.value.trim().toLowerCase()
  const list = library.playlists
  return q ? list.filter((p) => p.title.toLowerCase().includes(q)) : list
})

async function createPlaylist() {
  const p = await library.createPlaylist('My Playlist #' + (library.playlists.length + 1))
  // navigation handled by user click; keep it simple
}
</script>

<template>
  <nav class="sidebar">
    <div class="sidebar__library">
      <div class="sidebar__libhead">
        <div class="sidebar__libtitle">
          <Icon name="library" :size="24" /> <span>Your Library</span>
        </div>
        <button v-if="auth.isAuthenticated" class="sidebar__add" title="Create playlist" @click="createPlaylist">
          <Icon name="plus" :size="18" />
        </button>
      </div>

      <template v-if="auth.isAuthenticated">
        <div class="sidebar__pills">
          <button class="pill pill--active">Playlists</button>
          <button class="pill">Artists</button>
          <button class="pill">Albums</button>
        </div>

        <input v-model="filter" class="sidebar__filter" placeholder="Search in Your Library" />

        <div class="sidebar__list">
          <RouterLink to="/liked" class="libitem libitem--liked" :class="{ active: route.name === 'liked' }">
            <div class="libitem__cover libitem__cover--liked">
              <Icon name="heartFill" :size="20" />
            </div>
            <div class="libitem__meta">
              <div class="libitem__title">Liked Songs</div>
              <div class="libitem__sub">Playlist</div>
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
              <div class="libitem__sub">Playlist · {{ p.owner?.name }}</div>
            </div>
          </RouterLink>
        </div>
      </template>

      <div v-else class="sidebar__signin">
        <p>Log in to see your library.</p>
        <RouterLink to="/login" class="btn-primary" style="display:inline-block;margin-top:12px">Log in</RouterLink>
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
.sidebar__top {
  background: var(--bg-elevated);
  border-radius: var(--radius);
  padding: 8px 12px;
}
.sidebar__nav {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 12px;
  font-weight: 700;
  color: var(--text-subdued);
  border-radius: 4px;
}
.sidebar__nav:hover,
.sidebar__nav.active {
  color: #fff;
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
  color: var(--text-subdued);
}
.sidebar__add {
  color: var(--text-subdued);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
}
.sidebar__add:hover {
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
  background: var(--bg-highlight);
  color: #fff;
  border-radius: 999px;
  padding: 6px 12px;
  font-size: 13px;
  white-space: nowrap;
}
.pill:hover {
  background: #2a2a2a;
}
.pill--active {
  background: #fff;
  color: #000;
}
.sidebar__filter {
  margin: 4px 8px 8px 4px;
  background: var(--bg-highlight);
  border: none;
  border-radius: 4px;
  padding: 8px 12px;
  color: #fff;
  font-size: 13px;
}
.sidebar__filter::placeholder {
  color: var(--text-muted);
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
}
.sidebar__signin {
  padding: 16px;
  color: var(--text-subdued);
  font-size: 14px;
}
</style>
