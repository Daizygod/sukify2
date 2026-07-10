<script setup>
import { ref, onMounted, computed } from 'vue'
import api from '@/lib/api'
import TrackRow from '@/components/TrackRow.vue'
import { formatRelativeDate } from '@/lib/format'

const items = ref([])
const loading = ref(true)

onMounted(async () => {
  try {
    const { data } = await api.get('/me/history')
    items.value = data.data
  } finally {
    loading.value = false
  }
})

// Группировка по дню.
const groups = computed(() => {
  const map = new Map()
  for (const i of items.value) {
    const day = new Date(i.played_at).toLocaleDateString('ru-RU', {
      day: 'numeric',
      month: 'long',
    })
    if (!map.has(day)) map.set(day, [])
    map.get(day).push(i)
  }
  return [...map.entries()]
})

const allTracks = computed(() => items.value.map((i) => i.track))
</script>

<template>
  <div class="content-pad history">
    <h1 class="history__title">История прослушивания</h1>

    <section v-for="[day, list] in groups" :key="day" class="history__day">
      <h2 class="history__dayhead">{{ day }}</h2>
      <div class="history__list">
        <div v-for="(i, idx) in list" :key="idx" class="history__row">
          <TrackRow :track="i.track" :index="idx" variant="artist" :context-tracks="allTracks" context-name="История" />
          <span class="history__time muted">{{ formatRelativeDate(i.played_at) }}</span>
        </div>
      </div>
    </section>

    <p v-if="!loading && !items.length" class="muted">
      Пока пусто — включи что-нибудь, и история появится здесь.
    </p>
  </div>
</template>

<style scoped>
.history__title {
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin-bottom: 20px;
}
.history__dayhead {
  font-size: 17px;
  font-weight: 700;
  margin: 20px 0 8px;
}
.history__row {
  position: relative;
}
.history__time {
  position: absolute;
  right: 140px;
  top: 50%;
  translate: 0 -50%;
  font-size: 12px;
  pointer-events: none;
}
</style>
