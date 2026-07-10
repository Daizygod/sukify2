<script setup>
import { ref, onMounted, computed } from 'vue'
import api from '@/lib/api'
import CollectionHero from '@/components/CollectionHero.vue'
import TrackRow from '@/components/TrackRow.vue'
import TransitionSpot from '@/components/TransitionSpot.vue'
import Icon from '@/components/Icon.vue'
import { useTransitionInfo } from '@/lib/useTransitions'
import { trackCount, formatTotalDuration } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useAuthStore } from '@/stores/auth'

const player = usePlayerStore()
const auth = useAuthStore()
const tracks = ref([])
const loading = ref(true)

const totalMs = computed(() => tracks.value.reduce((a, t) => a + (t.duration_ms || 0), 0))

const { info: tinfo, load: loadTinfo, keyFor } = useTransitionInfo()

onMounted(async () => {
  try {
    const { data } = await api.get('/library/liked-tracks')
    tracks.value = data.data
    loadTinfo(tracks.value)
  } finally {
    loading.value = false
  }
})

function playAll() {
  if (tracks.value.length) player.playContext(tracks.value, 0, { name: 'Любимые треки' })
}
</script>

<template>
  <div class="liked">
    <CollectionHero kind="Плейлист" title="Любимые треки" bg="#5039ab">
      <template #cover>
        <div class="liked__cover"><Icon name="heartFill" :size="80" /></div>
      </template>
      <template #meta>
        <strong>{{ auth.user?.name }}</strong>
        <span>• {{ trackCount(tracks.length) }},</span>
        <span class="muted">{{ formatTotalDuration(totalMs, true) }}</span>
      </template>
    </CollectionHero>

    <div class="liked__body">
      <div class="liked__actions">
        <div class="liked__actions-left">
          <button class="play-btn play-btn--lg" @click="playAll"><Icon name="playBig" :size="24" /></button>
          <button class="ctl-lg" title="В случайном порядке"><Icon name="shuffleBig" :size="32" /></button>
          <button class="ctl-lg" title="Скачать"><Icon name="downloadCircle" :size="32" /></button>
        </div>
        <button class="liked__view">
          <span>Список</span>
          <Icon name="list" :size="16" />
        </button>
      </div>
      <div class="tracklist">
        <div class="tracktable__head trackgrid trackgrid--playlist">
          <div>#</div>
          <div>Название</div>
          <div>Альбом</div>
          <div>Дата добавления</div>
          <div class="th--right"><Icon name="clock" :size="16" /></div>
        </div>
        <template v-for="(t, i) in tracks" :key="t.id">
          <TrackRow
            :track="t"
            :index="i"
            :context-tracks="tracks"
            context-name="Любимые треки"
          />
          <TransitionSpot
            v-if="i < tracks.length - 1"
            :from="t"
            :to="tracks[i + 1]"
            :info="tinfo[keyFor(t, tracks[i + 1])]"
            @changed="loadTinfo(tracks)"
          />
        </template>
      </div>
      <p v-if="!loading && !tracks.length" class="muted" style="padding:24px">
        Треки, которые тебе понравились, появятся здесь.
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
  background: linear-gradient(180deg, rgba(80, 57, 171, 0.5) 0, #121212 260px);
  padding: 24px;
  min-height: 400px;
}
.liked__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.liked__actions-left {
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
.liked__view {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-subdued);
  font-size: 13px;
}
.liked__view:hover {
  color: #fff;
}
</style>
