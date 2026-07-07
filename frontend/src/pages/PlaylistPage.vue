<script setup>
import { ref, watch, computed } from 'vue'
import { useRoute } from 'vue-router'
import draggable from 'vuedraggable'
import api from '@/lib/api'
import CollectionHero from '@/components/CollectionHero.vue'
import TrackRow from '@/components/TrackRow.vue'
import Icon from '@/components/Icon.vue'
import { usePlayerStore } from '@/stores/player'

const route = useRoute()
const player = usePlayerStore()
const playlist = ref(null)
const items = ref([])
const loading = ref(true)

const isOwner = computed(() => playlist.value?.is_owner)

async function load(id) {
  loading.value = true
  try {
    const { data } = await api.get(`/playlists/${id}`)
    playlist.value = data.data
    items.value = data.data.tracks || []
  } finally {
    loading.value = false
  }
}
watch(() => route.params.id, (id) => id && load(id), { immediate: true })

function playAll() {
  if (items.value.length) player.playContext(items.value, 0)
}

async function onReorder() {
  const ids = items.value.map((t) => t.playlist_item_id)
  await api.put(`/playlists/${playlist.value.id}/order`, { item_ids: ids })
}

async function removeItem(track) {
  items.value = items.value.filter((t) => t.playlist_item_id !== track.playlist_item_id)
  await api.delete(`/playlists/${playlist.value.id}/tracks/${track.playlist_item_id}`)
}
</script>

<template>
  <div v-if="playlist" class="playlist">
    <CollectionHero
      kind="Playlist"
      :title="playlist.title"
      :cover="playlist.cover_url ? { 640: playlist.cover_url } : null"
      bg="#535353"
    >
      <template #meta>
        <strong>{{ playlist.owner?.name }}</strong>
        <span>· {{ items.length }} songs</span>
      </template>
    </CollectionHero>

    <div class="playlist__body">
      <div class="playlist__actions">
        <button class="play-btn" @click="playAll"><Icon name="play" :size="24" /></button>
      </div>

      <div class="tracklist">
        <div class="tracklist__head">
          <div>#</div><div>Title</div><div>Album</div><div></div><div><Icon name="clock" :size="16" /></div>
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
              <TrackRow :track="element" :index="index" :context-tracks="items" />
            </div>
          </template>
        </draggable>

        <template v-else>
          <TrackRow
            v-for="(t, i) in items"
            :key="t.playlist_item_id"
            :track="t"
            :index="i"
            :context-tracks="items"
          />
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.playlist__body {
  background: linear-gradient(180deg, rgba(83, 83, 83, 0.55) 0, #121212 260px);
  padding: 24px;
  min-height: 400px;
}
.playlist__actions {
  margin-bottom: 24px;
}
.pl-row {
  cursor: grab;
}
.tracklist__head {
  display: grid;
  grid-template-columns: 40px 1fr 22% 40px 60px;
  gap: 12px;
  padding: 4px 16px 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  color: var(--text-subdued);
  font-size: 13px;
  margin-bottom: 8px;
}
.tracklist__head > div:last-child {
  display: flex;
  justify-content: flex-end;
}
</style>
