<script setup>
import { ref, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import api from '@/lib/api'
import CoverImage from '@/components/CoverImage.vue'
import { formatNumber, plural } from '@/lib/format'

const stats = ref(null)

onMounted(async () => {
  const { data } = await api.get('/me/stats')
  stats.value = data
})
</script>

<template>
  <div class="content-pad stats">
    <h1 class="stats__title">Твоя статистика</h1>
    <p class="muted">За последние 30 дней</p>

    <template v-if="stats">
      <div class="stats__tiles">
        <div class="stats__tile">
          <div class="stats__num">{{ formatNumber(stats.total_plays) }}</div>
          <div class="muted">{{ plural(stats.total_plays, 'прослушивание', 'прослушивания', 'прослушиваний') }}</div>
        </div>
        <div class="stats__tile">
          <div class="stats__num">{{ formatNumber(stats.minutes) }}</div>
          <div class="muted">{{ plural(stats.minutes, 'минута', 'минуты', 'минут') }} музыки</div>
        </div>
      </div>

      <div class="stats__cols">
        <section v-if="stats.top_tracks.length">
          <h2 class="section-title">Топ треков</h2>
          <div v-for="(i, idx) in stats.top_tracks" :key="i.track.id" class="stats__row">
            <span class="stats__rank">{{ idx + 1 }}</span>
            <CoverImage :cover="i.track.cover" :size="40" class="stats__cover" />
            <div class="stats__meta">
              <div class="stats__name">{{ i.track.title }}</div>
              <div class="muted stats__sub">{{ i.track.artists.map((a) => a.name).join(', ') }}</div>
            </div>
            <span class="muted stats__plays">{{ i.plays }} {{ plural(i.plays, 'раз', 'раза', 'раз') }}</span>
          </div>
        </section>

        <section v-if="stats.top_artists.length">
          <h2 class="section-title">Топ исполнителей</h2>
          <RouterLink
            v-for="(i, idx) in stats.top_artists"
            :key="i.artist.id"
            :to="{ name: 'artist', params: { slug: i.artist.slug } }"
            class="stats__row"
          >
            <span class="stats__rank">{{ idx + 1 }}</span>
            <CoverImage :cover="i.artist.avatar_url ? { 300: i.artist.avatar_url } : null" :size="40" rounded class="stats__cover" />
            <div class="stats__meta">
              <div class="stats__name">{{ i.artist.name }}</div>
            </div>
            <span class="muted stats__plays">{{ i.plays }} {{ plural(i.plays, 'раз', 'раза', 'раз') }}</span>
          </RouterLink>
        </section>
      </div>

      <p v-if="!stats.total_plays" class="muted" style="margin-top: 24px">
        Пока нечего показать — послушай что-нибудь!
      </p>
    </template>
  </div>
</template>

<style scoped>
.stats__title {
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.02em;
}
.stats__tiles {
  display: flex;
  gap: 20px;
  margin: 24px 0 32px;
}
.stats__tile {
  background: var(--bg-card);
  border-radius: 8px;
  padding: 20px 28px;
  min-width: 180px;
}
.stats__num {
  font-size: 40px;
  font-weight: 800;
  color: var(--accent);
  letter-spacing: -0.02em;
}
.stats__cols {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
  gap: 32px;
  max-width: 1000px;
}
.stats__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px;
  border-radius: 4px;
}
a.stats__row:hover {
  background: rgba(255, 255, 255, 0.07);
}
.stats__rank {
  width: 20px;
  text-align: right;
  color: var(--text-subdued);
  font-variant-numeric: tabular-nums;
}
.stats__cover {
  width: 40px;
  flex: 0 0 40px;
  border-radius: 4px;
}
.stats__meta {
  min-width: 0;
  flex: 1;
}
.stats__name {
  font-weight: 600;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.stats__sub {
  font-size: 12px;
}
.stats__plays {
  font-size: 14px;
  white-space: nowrap;
}
</style>
