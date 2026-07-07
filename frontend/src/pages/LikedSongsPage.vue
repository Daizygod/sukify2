<script setup>
import { ref, onMounted } from 'vue'
import api from '@/lib/api'
import CollectionHero from '@/components/CollectionHero.vue'
import TrackRow from '@/components/TrackRow.vue'
import Icon from '@/components/Icon.vue'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'

const player = usePlayerStore()
const auth = useAuthStore()
const tracks = ref([])
const loading = ref(true)

onMounted(async () => {
  try {
    const { data } = await api.get('/library/liked-tracks')
    tracks.value = data.data
  } finally {
    loading.value = false
  }
})

function playAll() {
  if (tracks.value.length) player.playContext(tracks.value, 0)
}
</script>

<template>
  <div class="liked">
    <CollectionHero kind="Playlist" title="Liked Songs" bg="#4210a0">
      <template #cover>
        <div class="liked__cover"><Icon name="heartFill" :size="80" /></div>
      </template>
      <template #meta>
        <strong>{{ auth.user?.name }}</strong>
        <span>· {{ tracks.length }} songs</span>
      </template>
    </CollectionHero>

    <div class="liked__body">
      <div class="liked__actions">
        <button class="play-btn" @click="playAll"><Icon name="play" :size="24" /></button>
      </div>
      <div class="tracklist">
        <div class="tracklist__head">
          <div>#</div><div>Title</div><div>Album</div><div></div><div><Icon name="clock" :size="16" /></div>
        </div>
        <TrackRow
          v-for="(t, i) in tracks"
          :key="t.id"
          :track="t"
          :index="i"
          :context-tracks="tracks"
        />
      </div>
      <p v-if="!loading && !tracks.length" class="muted" style="padding:24px">
        Songs you like will appear here.
      </p>
    </div>
  </div>
</template>

<style scoped>
.liked__cover {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #450af5, #c4efd9);
  color: #fff;
}
.liked__body {
  background: linear-gradient(180deg, rgba(66, 16, 160, 0.5) 0, #121212 260px);
  padding: 24px;
  min-height: 400px;
}
.liked__actions {
  margin-bottom: 24px;
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
