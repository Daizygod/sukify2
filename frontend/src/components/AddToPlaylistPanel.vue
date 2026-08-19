<script setup>
import { ref, watch, computed, onMounted } from 'vue'
import api from '@/lib/api'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import { useUiStore } from '@/stores/ui'
import { useLibraryStore } from '@/stores/library'
import { useToastStore } from '@/stores/toasts'

/**
 * Правая панель «Добавить в этот плейлист» (аналог Spotify): по умолчанию —
 * любимые треки, которых в плейлисте ещё нет, а строка поиска ищет по всему
 * каталогу Sukify.
 */
const props = defineProps({
  playlistId: { type: [Number, String], required: true },
})

const ui = useUiStore()
const library = useLibraryStore()
const toasts = useToastStore()

const q = ref('')
const liked = ref([])
const found = ref([])
const loading = ref(false)
const adding = ref(new Set())

// Что уже лежит в плейлисте — этого не предлагаем.
const inPlaylist = ref(new Set())
const items = computed(() => {
  const list = q.value.trim() ? found.value : liked.value
  return list.filter((t) => !inPlaylist.value.has(t.id))
})

async function loadLiked() {
  try {
    const { data } = await api.get('/library/liked-tracks')
    liked.value = data.data || []
  } catch {
    liked.value = []
  }
}

async function loadExisting() {
  try {
    const { data } = await api.get(`/playlists/${props.playlistId}`)
    inPlaylist.value = new Set((data.data.tracks || []).map((t) => t.id))
  } catch {
    inPlaylist.value = new Set()
  }
}

onMounted(() => {
  loadLiked()
  loadExisting()
})

let timer
watch(q, (v) => {
  clearTimeout(timer)
  const query = v.trim()
  if (!query) {
    found.value = []
    return
  }
  loading.value = true
  timer = setTimeout(async () => {
    try {
      const { data } = await api.get('/search', { params: { q: query } })
      found.value = data.tracks || []
    } finally {
      loading.value = false
    }
  }, 300)
})

async function add(track) {
  if (adding.value.has(track.id)) return
  adding.value = new Set(adding.value).add(track.id)
  try {
    await library.addToPlaylist(props.playlistId, track.id)
    inPlaylist.value = new Set(inPlaylist.value).add(track.id)
    // Открытая страница плейлиста перечитает состав.
    ui.playlistRevision++
    toasts.show(`«${track.title}» добавлен`)
  } catch {
    toasts.show('Не удалось добавить')
  } finally {
    const next = new Set(adding.value)
    next.delete(track.id)
    adding.value = next
  }
}
</script>

<template>
  <aside class="atp">
    <div class="atp__head">
      <h2 class="atp__title">Добавить в этот плейлист</h2>
      <button class="atp__close" title="Закрыть" @click="ui.closeRight()">
        <Icon name="close" :size="16" />
      </button>
    </div>

    <div class="atp__field">
      <Icon name="searchSmall" :size="16" />
      <input v-model="q" placeholder="Поиск по Sukify" />
      <button v-if="q" class="atp__clear" title="Очистить" @click="q = ''">
        <Icon name="close" :size="12" />
      </button>
    </div>

    <div class="atp__kicker">
      {{ q.trim() ? 'Результаты поиска' : 'Из любимых треков' }}
    </div>

    <div class="atp__list">
      <div v-for="t in items" :key="t.id" class="atp__row">
        <CoverImage :cover="t.cover" :size="40" class="atp__cover" />
        <div class="atp__meta">
          <div class="atp__name">{{ t.title }}</div>
          <div class="atp__artists">{{ (t.artists || []).map((a) => a.name).join(', ') }}</div>
        </div>
        <button class="atp__add" title="Добавить" @click="add(t)">
          <Icon name="plus" :size="14" />
        </button>
      </div>

      <p v-if="loading" class="muted atp__empty">Ищем…</p>
      <p v-else-if="!items.length && q.trim()" class="muted atp__empty">Ничего не нашлось.</p>
      <p v-else-if="!items.length" class="muted atp__empty">
        Все твои любимые треки уже в плейлисте — поищи что-нибудь ещё.
      </p>
    </div>
  </aside>
</template>

<style scoped>
.atp {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 16px;
  gap: 12px;
  min-height: 0;
}
.atp__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.atp__title {
  font-size: 16px;
  font-weight: 700;
}
.atp__close {
  color: var(--text-subdued);
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
}
.atp__close:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.atp__field {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #2a2a2a;
  border-radius: 4px;
  padding: 10px 12px;
  color: var(--text-subdued);
}
.atp__field input {
  flex: 1;
  background: transparent;
  border: none;
  outline: none;
  color: #fff;
  font-size: 14px;
  font-family: inherit;
  min-width: 0;
}
.atp__clear {
  color: var(--text-subdued);
  display: grid;
  place-items: center;
}
.atp__clear:hover {
  color: #fff;
}
.atp__kicker {
  color: var(--text-subdued);
  font-size: 13px;
}
.atp__list {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}
.atp__row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px;
  border-radius: 4px;
}
.atp__row:hover {
  background: rgba(255, 255, 255, 0.07);
}
.atp__cover {
  width: 40px;
  flex: 0 0 40px;
  border-radius: 4px;
}
.atp__meta {
  flex: 1;
  min-width: 0;
}
.atp__name {
  font-size: 14px;
  color: #fff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.atp__artists {
  font-size: 12px;
  color: var(--text-subdued);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.atp__add {
  flex: 0 0 auto;
  color: #fff;
  border: 1px solid var(--text-subdued);
  border-radius: 999px;
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
}
.atp__add:hover {
  border-color: #fff;
  transform: scale(1.05);
}
.atp__empty {
  padding: 12px 6px;
  font-size: 13px;
}
</style>
