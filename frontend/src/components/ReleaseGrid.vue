<script setup>
import { ref, watch, onBeforeUnmount, onMounted } from 'vue'
import api from '@/lib/api'
import MediaCard from './MediaCard.vue'

/**
 * Весь каталог релизов с бесконечной подгрузкой. Используется и в конце
 * страницы обзора, и на отдельной странице «Все релизы».
 */
const props = defineProps({
  sort: { type: String, default: 'added' }, // added | date | name
})

const items = ref([])
const page = ref(0)
const lastPage = ref(1)
const loading = ref(false)
const sentinel = ref(null)
let observer = null

async function loadMore() {
  if (loading.value || page.value >= lastPage.value) return
  loading.value = true
  try {
    const { data } = await api.get('/releases', {
      params: { page: page.value + 1, sort: props.sort },
    })
    items.value.push(...data.data)
    page.value = data.meta.current_page
    lastPage.value = data.meta.last_page
  } finally {
    loading.value = false
  }
}

function reset() {
  items.value = []
  page.value = 0
  lastPage.value = 1
  loadMore()
}
watch(() => props.sort, reset)

async function releaseTracks(r) {
  const { data } = await api.get(`/releases/${r.slug}`)
  return data.data.tracks || []
}

onMounted(() => {
  reset()
  // Подгружаем следующую страницу, когда «маячок» под сеткой входит в экран.
  observer = new IntersectionObserver(
    (entries) => entries[0].isIntersecting && loadMore(),
    { rootMargin: '600px' }
  )
  if (sentinel.value) observer.observe(sentinel.value)
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <div>
    <div class="grid-cards">
      <MediaCard
        v-for="r in items"
        :key="r.id"
        :to="{ name: 'release', params: { slug: r.slug } }"
        :cover="r.cover"
        :title="r.title"
        :subtitle="`${r.year || ''} · ${r.artist?.name || ''}`"
        :context-key="`release:${r.slug}`"
        :tracks="() => releaseTracks(r)"
        :context-name="r.title"
      />
    </div>
    <div ref="sentinel" class="relgrid__sentinel"></div>
    <p v-if="loading" class="muted">Загружаем…</p>
    <p v-else-if="!items.length" class="muted">Здесь пока пусто.</p>
  </div>
</template>

<style scoped>
.relgrid__sentinel {
  height: 1px;
}
</style>
