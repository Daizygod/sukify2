<script setup>
import { ref, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import draggable from 'vuedraggable'
import api from '@/lib/api'
import CollectionHero from '@/components/CollectionHero.vue'
import TrackRow from '@/components/TrackRow.vue'
import TransitionSpot from '@/components/TransitionSpot.vue'
import Icon from '@/components/Icon.vue'
import { useTransitionInfo } from '@/lib/useTransitions'
import { trackCount, formatTotalDuration } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'
import { useToastStore } from '@/stores/toasts'
import { useLibraryStore } from '@/stores/library'
import { downloadTracks } from '@/lib/download'
import HeroMenu from '@/components/HeroMenu.vue'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()
const ui = useUiStore()
const toasts = useToastStore()
const library = useLibraryStore()
const playlist = ref(null)
const items = ref([])
const loading = ref(true)

const isOwner = computed(() => playlist.value?.is_owner)

// Spotify tints the playlist hero with the cover palette — reuse the first
// track's release color, which the cover collage is built from.
const heroBg = computed(
  () => items.value[0]?.release?.colors?.background || '#535353'
)

const totalMs = computed(() => items.value.reduce((a, t) => a + (t.duration_ms || 0), 0))

const { info: tinfo, load: loadTinfo, keyFor } = useTransitionInfo()

async function load(id) {
  loading.value = true
  try {
    const { data } = await api.get(`/playlists/${id}`)
    playlist.value = data.data
    items.value = data.data.tracks || []
    loadTinfo(items.value)
  } finally {
    loading.value = false
  }
}
watch(() => route.params.id, (id) => id && load(id), { immediate: true })

function playAll() {
  if (items.value.length) player.playContext(items.value, 0, { name: playlist.value?.title })
}

async function onReorder() {
  const ids = items.value.map((t) => t.playlist_item_id)
  await api.put(`/playlists/${playlist.value.id}/order`, { item_ids: ids })
  loadTinfo(items.value)
}

function playShuffled() {
  player.setShuffle(true)
  playAll()
}

const shareLink = computed(() =>
  playlist.value ? `${location.origin}/playlist/${playlist.value.id}` : location.origin
)

async function download() {
  toasts.show(`Скачиваю: ${items.value.length} трек(ов)…`)
  const n = await downloadTracks(items.value)
  toasts.show(`Скачано файлов: ${n}`)
}

async function removePlaylist() {
  if (!confirm(`Удалить плейлист «${playlist.value.title}»?`)) return
  await api.delete(`/playlists/${playlist.value.id}`)
  await library.refreshPlaylists()
  toasts.show('Плейлист удалён')
  router.push('/')
}
</script>

<template>
  <div v-if="playlist" class="playlist">
    <CollectionHero
      :kind="playlist.is_public ? 'Открытый плейлист' : 'Закрытый плейлист'"
      :title="playlist.title"
      :cover="playlist.cover_url ? { 640: playlist.cover_url } : null"
      :bg="heroBg"
    >
      <template #meta>
        <strong>{{ playlist.owner?.name }}</strong>
        <span>• {{ trackCount(items.length) }},</span>
        <span class="muted">{{ formatTotalDuration(totalMs, true) }}</span>
      </template>
    </CollectionHero>

    <div class="playlist__body" :style="{ '--body-bg': heroBg }">
      <div class="playlist__actions">
        <div class="playlist__actions-left">
          <button class="play-btn play-btn--lg" @click="playAll"><Icon name="playBig" :size="24" /></button>
          <button class="ctl-lg" :class="{ on: player.shuffle }" title="В случайном порядке" @click="playShuffled"><Icon name="shuffleBig" :size="32" /></button>
          <button class="ctl-lg" title="Скачать" @click="download"><Icon name="downloadCircle" :size="32" /></button>
          <HeroMenu :tracks="items" :link="shareLink" :can-delete="isOwner" @delete="removePlaylist" />
        </div>
        <button class="playlist__view" @click="ui.toggleListCompact()">
          <span>{{ ui.listCompact ? 'Компактный' : 'Список' }}</span>
          <Icon name="list" :size="16" />
        </button>
      </div>

      <div class="tracklist" :class="{ 'tracklist--compact': ui.listCompact }">
        <div class="tracktable__head trackgrid trackgrid--playlist">
          <div>#</div>
          <div>Название</div>
          <div>Альбом</div>
          <div>Дата добавления</div>
          <div class="th--right"><Icon name="clock" :size="16" /></div>
        </div>

        <draggable
          v-if="isOwner"
          v-model="items"
          item-key="playlist_item_id"
          handle=".pl-row"
          @end="onReorder"
        >
          <template #item="{ element, index }">
            <div class="pl-row">
              <TrackRow :track="element" :index="index" :context-tracks="items" :context-name="playlist.title" />
              <TransitionSpot
                v-if="index < items.length - 1"
                :from="element"
                :to="items[index + 1]"
                :info="tinfo[keyFor(element, items[index + 1])]"
                @changed="loadTinfo(items)"
              />
            </div>
          </template>
        </draggable>

        <template v-else>
          <template v-for="(t, i) in items" :key="t.playlist_item_id">
            <TrackRow
              :track="t"
              :index="i"
              :context-tracks="items"
              :context-name="playlist.title"
            />
            <TransitionSpot
              v-if="i < items.length - 1"
              :from="t"
              :to="items[i + 1]"
              :info="tinfo[keyFor(t, items[i + 1])]"
              @changed="loadTinfo(items)"
            />
          </template>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.playlist__body {
  background: linear-gradient(180deg, color-mix(in srgb, var(--body-bg) 40%, #121212) 0, #121212 260px);
  padding: 24px;
  min-height: 400px;
}
.playlist__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.playlist__actions-left {
  display: flex;
  align-items: center;
  gap: 24px;
}
.play-btn--lg {
  width: 56px;
  height: 56px;
}
.ctl-lg {
  color: var(--text-subdued);
  display: grid;
  place-items: center;
}
.ctl-lg:hover {
  color: #fff;
  transform: scale(1.04);
}
.playlist__view {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-subdued);
  font-size: 13px;
}
.playlist__view:hover {
  color: #fff;
}
.pl-row {
  cursor: grab;
}
</style>
