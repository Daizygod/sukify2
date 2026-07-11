<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import Icon from './Icon.vue'
import { useMenuStore } from '@/stores/menu'
import { useLibraryStore } from '@/stores/library'
import { usePlayerStore } from '@/stores/player'
import { useToastStore } from '@/stores/toasts'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { useDeviceStore } from '@/stores/devices'
import api from '@/lib/api'

const menu = useMenuStore()
const library = useLibraryStore()
const player = usePlayerStore()
const toasts = useToastStore()
const auth = useAuthStore()
const ui = useUiStore()
const router = useRouter()

const rootEl = ref(null)
const submenuOpen = ref(false)
const plFilter = ref('')
const pos = ref({ x: 0, y: 0 })

const track = computed(() => menu.track)
const entity = computed(() => menu.entity)
const liked = computed(() => track.value && library.isLiked(track.value.id))
const artist = computed(() => track.value?.artists?.[0] || null)

// --- Меню сущности (альбом/плейлист/микс/исполнитель/любимые) --------------

async function entityTracks(e) {
  switch (e.type) {
    case 'release': {
      const { data } = await api.get(`/releases/${e.slug}`)
      return data.data.tracks || []
    }
    case 'playlist': {
      const { data } = await api.get(`/playlists/${e.id}`)
      return data.data.tracks || []
    }
    case 'mix': {
      const { data } = await api.get(`/mixes/daily/${e.n}`)
      return data.tracks || []
    }
    case 'artist': {
      const { data } = await api.get(`/artists/${e.slug}/top-tracks`)
      return data.data || []
    }
    case 'liked': {
      const { data } = await api.get('/library/liked-tracks')
      return data.data || []
    }
  }
  return []
}

async function entityQueue() {
  const e = entity.value
  menu.close()
  try {
    const tracks = await entityTracks(e)
    const devices = useDeviceStore()
    if (devices.isRemote) {
      devices.sendCommand('queue-add', tracks.map((t) => t.id))
      toasts.show(`Очередь отправлена на «${devices.activeDevice?.name || 'устройство'}»`)
    } else {
      let n = 0
      for (const t of tracks) if (player.addToQueue(t)) n++
      toasts.show(`В очередь добавлено: ${n}`)
    }
  } catch {
    toasts.show('Не получилось добавить в очередь')
  }
}

async function entityPlay() {
  const e = entity.value
  menu.close()
  try {
    const tracks = await entityTracks(e)
    if (tracks.length) player.playContext(tracks, 0, { name: e.title })
  } catch {
    toasts.show('Не получилось включить')
  }
}

async function entityPin() {
  const e = entity.value
  menu.close()
  const pinned = await library.togglePin(e.pinType, e.pinId)
  toasts.show(pinned ? 'Закреплено в медиатеке' : 'Откреплено')
}

async function entityShare() {
  const e = entity.value
  menu.close()
  const path = {
    release: `/release/${e.slug}`,
    playlist: `/playlist/${e.id}`,
    artist: `/artist/${e.slug}`,
    mix: `/mix/${e.n}`,
    liked: '/liked',
  }[e.type] || '/'
  try {
    await navigator.clipboard.writeText(location.origin + path)
    toasts.show('Ссылка скопирована в буфер обмена')
  } catch {
    toasts.show(location.origin + path)
  }
}

// Open the playlist submenu toward whichever side has room.
const subLeft = computed(() => pos.value.x > window.innerWidth - 620)

const filteredPlaylists = computed(() => {
  const q = plFilter.value.trim().toLowerCase()
  const own = library.playlists
  return q ? own.filter((p) => p.title.toLowerCase().includes(q)) : own
})

watch(
  () => menu.open,
  async (open) => {
    submenuOpen.value = false
    plFilter.value = ''
    if (!open) return
    pos.value = { x: menu.x, y: menu.y }
    await nextTick()
    // Clamp to the viewport.
    const el = rootEl.value
    if (!el) return
    const r = el.getBoundingClientRect()
    let { x, y } = pos.value
    if (x + r.width > window.innerWidth - 8) x = window.innerWidth - r.width - 8
    if (y + r.height > window.innerHeight - 8) y = window.innerHeight - r.height - 8
    pos.value = { x: Math.max(8, x), y: Math.max(8, y) }
  }
)

async function addToPlaylist(p) {
  menu.close()
  try {
    await library.addToPlaylist(p.id, track.value.id)
    toasts.show(`Добавлено в плейлист «${p.title}»`)
  } catch {
    toasts.show('Не получилось добавить — попробуй ещё раз')
  }
}

async function createAndAdd() {
  menu.close()
  const p = await library.createPlaylist('Мой плейлист №' + (library.playlists.length + 1))
  await library.addToPlaylist(p.id, track.value.id)
  toasts.show(`Создан плейлист «${p.title}»`)
}

function toggleLike() {
  const wasLiked = liked.value
  library.toggleLike(track.value)
  toasts.show(wasLiked ? 'Удалено из любимых треков' : 'Добавлено в любимые треки')
  menu.close()
}

function addToQueue() {
  const devices = useDeviceStore()
  if (devices.isRemote) {
    devices.sendCommand('queue-add', track.value.id)
    toasts.show(`Добавлено в очередь на «${devices.activeDevice?.name || 'устройстве'}»`)
  } else if (player.addToQueue(track.value)) {
    toasts.show('Добавлено в очередь')
  }
  menu.close()
}

function removeFromQueue() {
  if (menu.opts.queueQid != null) player.removeFromManualQueue(menu.opts.queueQid)
  else if (menu.opts.upcomingIndex != null) player.removeUpcoming(menu.opts.upcomingIndex)
  toasts.show('Удалено из очереди')
  menu.close()
}

function openTransitionPicker() {
  ui.transitionFrom = track.value
  menu.close()
}

async function startRadio() {
  menu.close()
  try {
    const { data } = await api.get(`/tracks/${track.value.id}/radio`)
    player.playContext([track.value, ...data.data], 0, { name: `Радио: ${track.value.title}` })
    toasts.show('Включаю радио по треку')
  } catch {
    toasts.show('Радио сейчас недоступно')
  }
}

function goArtist() {
  menu.close()
  if (artist.value) router.push({ name: 'artist', params: { slug: artist.value.slug } })
}

function goAlbum() {
  menu.close()
  if (track.value.release) router.push({ name: 'release', params: { slug: track.value.release.slug } })
}

async function copyLink() {
  menu.close()
  const rel = track.value.release
  const url = rel
    ? `${location.origin}/release/${rel.slug}?t=${track.value.id}`
    : location.origin
  try {
    await navigator.clipboard.writeText(url)
    toasts.show('Ссылка скопирована в буфер обмена')
  } catch {
    toasts.show(url)
  }
}
</script>

<template>
  <Teleport to="body">
    <!-- Меню сущности: альбом/плейлист/микс/исполнитель/любимые -->
    <div v-if="menu.open && entity" class="cm__backdrop" @click="menu.close()" @contextmenu.prevent="menu.close()">
      <div ref="rootEl" class="cm" :style="{ left: pos.x + 'px', top: pos.y + 'px' }" @click.stop>
        <div class="cm__entityhead">{{ entity.title }}</div>
        <button class="cm__item" @click="entityPlay">
          <Icon name="play" :size="16" />
          <span>Слушать</span>
        </button>
        <button class="cm__item" @click="entityQueue">
          <Icon name="queue" :size="16" />
          <span>Добавить в очередь</span>
        </button>
        <button v-if="entity.pinType && auth.isAuthenticated" class="cm__item" @click="entityPin">
          <Icon name="pin" :size="16" :class="{ cm__liked: library.isPinned(entity.pinType, entity.pinId) }" />
          <span>{{ library.isPinned(entity.pinType, entity.pinId) ? 'Открепить' : 'Закрепить в медиатеке' }}</span>
        </button>
        <div class="cm__divider"></div>
        <button class="cm__item" @click="entityShare">
          <Icon name="share" :size="16" />
          <span>Поделиться</span>
        </button>
      </div>
    </div>

    <div v-else-if="menu.open && track" class="cm__backdrop" @click="menu.close()" @contextmenu.prevent="menu.close()">
      <div
        ref="rootEl"
        class="cm"
        :style="{ left: pos.x + 'px', top: pos.y + 'px' }"
        @click.stop
      >
        <div
          v-if="auth.isAuthenticated"
          class="cm__item cm__item--sub"
          @mouseenter="submenuOpen = true"
          @mouseleave="submenuOpen = false"
        >
          <Icon name="plus" :size="16" />
          <span>Добавить в плейлист</span>
          <Icon name="chevronRight" :size="12" class="cm__arrow" />

          <div v-if="submenuOpen" class="cm cm--sub" :class="{ 'cm--subleft': subLeft }">
            <div class="cm__search">
              <Icon name="searchSmall" :size="14" />
              <input v-model="plFilter" placeholder="Поиск плейлиста" @click.stop />
            </div>
            <button class="cm__item" @click="createAndAdd">
              <Icon name="plus" :size="16" />
              <span>Создать плейлист</span>
            </button>
            <div class="cm__divider"></div>
            <div class="cm__scroll">
              <button v-for="p in filteredPlaylists" :key="p.id" class="cm__item" @click="addToPlaylist(p)">
                <span class="cm__pl-title">{{ p.title }}</span>
              </button>
            </div>
          </div>
        </div>

        <button v-if="auth.isAuthenticated" class="cm__item" @click="toggleLike">
          <Icon :name="liked ? 'checkCircle' : 'plusCircle'" :size="16" :class="{ cm__liked: liked }" />
          <span>{{ liked ? 'Удалить из любимых треков' : 'Добавить в любимые треки' }}</span>
        </button>

        <button class="cm__item" @click="addToQueue">
          <Icon name="queue" :size="16" />
          <span>Добавить в очередь</span>
        </button>

        <button
          v-if="menu.opts.queueQid != null || menu.opts.upcomingIndex != null"
          class="cm__item"
          @click="removeFromQueue"
        >
          <Icon name="caretDown" :size="16" style="transform: rotate(180deg)" />
          <span>Удалить из очереди</span>
        </button>

        <div class="cm__divider"></div>

        <button class="cm__item" @click="openTransitionPicker">
          <Icon name="shuffle" :size="16" />
          <span>Создать переход…</span>
        </button>
        <button class="cm__item" @click="startRadio">
          <Icon name="radio" :size="16" />
          <span>К радио по треку</span>
        </button>
        <button v-if="artist" class="cm__item" @click="goArtist">
          <Icon name="person" :size="16" />
          <span>К исполнителю</span>
        </button>
        <button v-if="track.release" class="cm__item" @click="goAlbum">
          <Icon name="album" :size="16" />
          <span>К альбому</span>
        </button>

        <div class="cm__divider"></div>

        <button class="cm__item" @click="copyLink">
          <Icon name="share" :size="16" />
          <span>Поделиться</span>
        </button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.cm__backdrop {
  position: fixed;
  inset: 0;
  z-index: 90;
}
.cm {
  position: fixed;
  min-width: 260px;
  max-width: 320px;
  background: #282828;
  border-radius: 4px;
  box-shadow: 0 16px 24px rgba(0, 0, 0, 0.4), 0 6px 8px rgba(0, 0, 0, 0.3);
  padding: 4px;
  z-index: 95;
}
.cm--sub {
  position: absolute;
  left: calc(100% - 4px);
  top: -4px;
  max-height: 420px;
  display: flex;
  flex-direction: column;
}
.cm--subleft {
  left: auto;
  right: calc(100% - 4px);
}

.cm__item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  border-radius: 2px;
  color: #ffffffe6;
  font-size: 14px;
  text-align: left;
  position: relative;
  cursor: default;
}
.cm__item:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}
.cm__item svg {
  flex: 0 0 auto;
  color: var(--text-subdued);
}
.cm__item:hover svg {
  color: #fff;
}
.cm__liked {
  color: var(--accent) !important;
}
.cm__arrow {
  margin-left: auto;
}
.cm__divider {
  height: 1px;
  background: rgba(255, 255, 255, 0.1);
  margin: 4px 0;
}
.cm__entityhead {
  font-weight: 700;
  font-size: 13px;
  color: var(--text-subdued);
  padding: 8px 12px 6px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cm__search {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #3e3e3e;
  border-radius: 4px;
  margin: 4px 8px 8px;
  padding: 8px 10px;
  color: var(--text-subdued);
}
.cm__search input {
  background: none;
  border: none;
  outline: none;
  color: #fff;
  font-size: 13px;
  width: 100%;
}
.cm__scroll {
  overflow-y: auto;
  min-height: 0;
}
.cm__pl-title {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
