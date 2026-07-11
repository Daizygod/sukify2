<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/lib/api'
import { computed } from 'vue'
import TrackRow from '@/components/TrackRow.vue'
import MediaCard from '@/components/MediaCard.vue'
import HeroMenu from '@/components/HeroMenu.vue'
import Icon from '@/components/Icon.vue'
import { usePlayerStore } from '@/stores/player'



const route = useRoute()
const player = usePlayerStore()
const artist = ref(null)
const tracks = ref([])
const releases = ref([])

async function load(slug) {
  const [{ data: a }, { data: liked }] = await Promise.all([
    api.get(`/artists/${slug}`),
    api.get(`/artists/${slug}/liked`),
  ])
  artist.value = a.data
  tracks.value = liked.tracks
  releases.value = liked.releases
}
watch(() => route.params.slug, (s) => s && load(s), { immediate: true })

function playAll() {
  if (tracks.value.length) {
    player.playContext(tracks.value, 0, { name: `Любимые треки: ${artist.value?.name}` })
  }
}

const shareLink = computed(() =>
  artist.value ? `${location.origin}/artist/${artist.value.slug}/liked` : location.origin
)
</script>

<template>
  <div v-if="artist" class="aliked content-pad">
    <div class="aliked__head">
      <button class="play-btn aliked__play" @click="playAll"><Icon name="playBig" :size="20" /></button>
      <h1 class="aliked__title">Любимые треки: {{ artist.name }}</h1>
      <HeroMenu :tracks="tracks" :link="shareLink" />
    </div>

    <div class="tracklist">
      <div class="tracktable__head trackgrid trackgrid--playlist">
        <div>#</div>
        <div>Название</div>
        <div>Альбом</div>
        <div>Дата добавления</div>
        <div class="th--right"><Icon name="clock" :size="16" /></div>
      </div>
      <TrackRow
        v-for="(t, i) in tracks"
        :key="t.id"
        :track="t"
        :index="i"
        :context-tracks="tracks"
        :context-name="`Любимые треки: ${artist.name}`"
      />
    </div>

    <section v-if="releases.length" class="aliked__releases">
      <h2 class="section-title">Понравившиеся релизы</h2>
      <div class="grid-cards">
        <MediaCard
          v-for="r in releases"
          :key="r.id"
          :to="{ name: 'release', params: { slug: r.slug } }"
          :cover="r.cover"
          :title="r.title"
          :subtitle="`${r.year || ''} • Альбом`"
        />
      </div>
    </section>

    <p v-if="!tracks.length" class="muted">
      Здесь появятся любимые треки этого исполнителя.
    </p>
  </div>
</template>

<style scoped>
.aliked__head {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 24px;
}
.aliked__play {
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
}
.aliked__title {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.02em;
}
.aliked__releases {
  margin-top: 36px;
}
</style>
