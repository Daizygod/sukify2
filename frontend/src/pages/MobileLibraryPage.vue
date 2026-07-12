<script setup>
import { ref, computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import Icon from '@/components/Icon.vue'
import CoverImage from '@/components/CoverImage.vue'
import { useAuthStore } from '@/stores/auth'
import { useLibraryStore } from '@/stores/library'
import { useMenuStore } from '@/stores/menu'
import { trackCount } from '@/lib/format'

const auth = useAuthStore()
const library = useLibraryStore()
const menuStore = useMenuStore()
const route = useRoute()

const activeTab = ref('') // '' | 'playlists' | 'artists' | 'albums'
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

// Тот же смешанный список, что и в сайдбаре на десктопе.
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
      })
    }
  }
  for (const i of out) i.pinned = library.isPinned(i.pinType, i.pinId)
  return [...out.filter((i) => i.pinned), ...out.filter((i) => !i.pinned)]
})

const showLiked = computed(() => !activeTab.value || activeTab.value === 'playlists')

function pickTab(id) {
  activeTab.value = activeTab.value === id ? '' : id
}

function openItemMenu(e, item) {
  const type = item.pinType === 'album' ? 'release' : item.pinType
  menuStore.openEntityMenu(e, {
    type,
    id: item.pinId,
    slug: item.to?.params?.slug,
    title: item.title,
    pinType: item.pinType,
    pinId: item.pinId,
  })
}

async function createPlaylist() {
  await library.createPlaylist('Мой плейлист №' + (library.playlists.length + 1))
}

const initial = computed(() => (auth.user?.name || '?').slice(0, 1).toUpperCase())
</script>

<template>
  <div class="mlib">
    <header class="mlib__head">
      <div class="mlib__ava">
        <img v-if="auth.user?.avatar_url" :src="auth.user.avatar_url" alt="" />
        <span v-else>{{ initial }}</span>
      </div>
      <h1>Моя медиатека</h1>
      <RouterLink to="/search" class="mlib__hbtn"><Icon name="searchSmall" :size="22" /></RouterLink>
      <button class="mlib__hbtn" title="Создать плейлист" @click="createPlaylist"><Icon name="plus" :size="22" /></button>
    </header>

    <div class="mlib__chips">
      <template v-if="activeTab">
        <button class="mlib__clear" @click="activeTab = ''">
          <Icon name="plus" :size="14" style="transform: rotate(45deg)" />
        </button>
        <button class="chip chip--active" @click="activeTab = ''">
          {{ TABS.find((t) => t.id === activeTab)?.label }}
        </button>
      </template>
      <template v-else>
        <button v-for="t in TABS" :key="t.id" class="chip" @click="pickTab(t.id)">{{ t.label }}</button>
      </template>
    </div>

    <div class="mlib__sort">
      <Icon name="list" :size="16" />
      <span>Недавние</span>
    </div>

    <div class="mlib__list">
      <RouterLink v-if="showLiked" to="/liked" class="mlibitem">
        <div class="mlibitem__cover mlibitem__cover--liked"><Icon name="heartFill" :size="24" /></div>
        <div class="mlibitem__meta">
          <div class="mlibitem__title">Любимые треки</div>
          <div class="mlibitem__sub">
            <Icon name="pin" :size="12" class="mlibitem__pin" />
            <span>Плейлист • {{ trackCount(library.likedTrackIds.size) }}</span>
          </div>
        </div>
      </RouterLink>

      <RouterLink
        v-for="item in items"
        :key="item.key"
        :to="item.to"
        class="mlibitem"
        @contextmenu.prevent="openItemMenu($event, item)"
      >
        <CoverImage :cover="item.cover" :size="80" class="mlibitem__cover" :rounded="item.round" />
        <div class="mlibitem__meta">
          <div class="mlibitem__title">{{ item.title }}</div>
          <div class="mlibitem__sub">
            <Icon v-if="item.pinned" name="pin" :size="12" class="mlibitem__pin" />
            <span>{{ item.sub }}</span>
          </div>
        </div>
      </RouterLink>

      <p v-if="!items.length" class="mlib__empty">Здесь пока пусто — добавь плейлисты и альбомы.</p>
    </div>
  </div>
</template>

<style scoped>
.mlib {
  padding: 12px 16px 24px;
}
.mlib__head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0 12px;
}
.mlib__head h1 {
  flex: 1;
  font-size: 24px;
  font-weight: 700;
}
.mlib__ava {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  overflow: hidden;
  background: #509bf5;
  color: #000;
  font-weight: 700;
  font-size: 15px;
  display: grid;
  place-items: center;
  flex: 0 0 34px;
}
.mlib__ava img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.mlib__hbtn {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  color: var(--text-subdued);
}
.mlib__chips {
  display: flex;
  align-items: center;
  gap: 8px;
  overflow-x: auto;
  scrollbar-width: none;
  padding-bottom: 12px;
}
.mlib__chips::-webkit-scrollbar {
  display: none;
}
.chip {
  background: #2a2a2a;
  color: #fff;
  border-radius: 999px;
  padding: 8px 14px;
  font-size: 13px;
  white-space: nowrap;
}
.chip--active {
  background: var(--accent);
  color: #000;
  font-weight: 600;
}
.mlib__clear {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: #2a2a2a;
  color: var(--text-subdued);
  display: grid;
  place-items: center;
  flex: 0 0 30px;
}
.mlib__sort {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  padding: 4px 0 10px;
}
.mlibitem {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
}
.mlibitem__cover {
  width: 56px;
  flex: 0 0 56px;
  border-radius: 4px;
}
.mlibitem__cover--liked {
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #450af5, #8e8ee5);
  color: #fff;
  border-radius: 4px;
}
.mlibitem__meta {
  min-width: 0;
}
.mlibitem__title {
  color: #fff;
  font-size: 16px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mlibitem__sub {
  color: var(--text-subdued);
  font-size: 13px;
  margin-top: 2px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.mlibitem__pin {
  color: var(--accent);
  flex: 0 0 12px;
}
.mlib__empty {
  color: var(--text-subdued);
  font-size: 14px;
  padding: 24px 0;
}
</style>
