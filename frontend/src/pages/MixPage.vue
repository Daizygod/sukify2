<script setup>
import { ref, watch, computed } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/lib/api'
import TrackRow from '@/components/TrackRow.vue'
import Icon from '@/components/Icon.vue'
import { trackCount, formatTotalDuration } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const player = usePlayerStore()
const ui = useUiStore()
const mix = ref(null)

const totalMs = computed(() => (mix.value?.tracks || []).reduce((a, t) => a + (t.duration_ms || 0), 0))

async function load(n) {
  const { data } = await api.get(`/mixes/daily/${n}`)
  mix.value = data
}
watch(() => route.params.n, (n) => n && load(n), { immediate: true })

function playAll() {
  if (mix.value?.tracks?.length) player.playContext(mix.value.tracks, 0, { name: mix.value.title })
}
</script>

<template>
  <div v-if="mix" class="mix">
    <div class="mix__hero" :style="{ '--mix-bg': mix.color }">
      <div class="mix__cover">
        <Icon name="nowplaying" :size="72" />
      </div>
      <div class="mix__text">
        <span class="mix__kind">Микс от Sukify</span>
        <h1 class="mix__title">{{ mix.title }}</h1>
        <div class="mix__meta">
          <strong>{{ mix.genre }}</strong>
          <span>• {{ trackCount(mix.tracks.length) }},</span>
          <span class="muted">{{ formatTotalDuration(totalMs, true) }}</span>
        </div>
      </div>
    </div>

    <div class="mix__body" :style="{ '--mix-bg': mix.color }">
      <div class="mix__actions">
        <button class="play-btn play-btn--lg" @click="playAll"><Icon name="playBig" :size="24" /></button>
      </div>
      <div class="tracklist" :class="{ 'tracklist--compact': ui.listCompact }">
        <div class="tracktable__head trackgrid trackgrid--playlist">
          <div>#</div>
          <div>Название</div>
          <div>Альбом</div>
          <div></div>
          <div class="th--right"><Icon name="clock" :size="16" /></div>
        </div>
        <TrackRow
          v-for="(t, i) in mix.tracks"
          :key="t.id"
          :track="t"
          :index="i"
          :context-tracks="mix.tracks"
          :context-name="mix.title"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.mix__hero {
  background: linear-gradient(180deg, var(--mix-bg) 0%, rgba(0, 0, 0, 0.55) 100%);
  display: flex;
  align-items: flex-end;
  gap: 24px;
  padding: 84px 24px 24px;
}
.mix__cover {
  width: 232px;
  height: 232px;
  flex: 0 0 232px;
  border-radius: 4px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.25), rgba(0, 0, 0, 0.25)), var(--mix-bg);
  display: grid;
  place-items: center;
  color: #fff;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5);
}
.mix__kind {
  font-size: 14px;
  font-weight: 600;
}
.mix__title {
  font-size: clamp(40px, 6vw, 80px);
  font-weight: 800;
  letter-spacing: -0.04em;
  margin: 12px 0;
}
.mix__meta {
  font-size: 14px;
  display: flex;
  gap: 6px;
}
.mix__body {
  background: linear-gradient(180deg, color-mix(in srgb, var(--mix-bg) 40%, #121212) 0, #121212 260px);
  padding: 24px;
  min-height: 400px;
}
.mix__actions {
  margin-bottom: 12px;
}
.play-btn--lg {
  width: 56px;
  height: 56px;
}
</style>
