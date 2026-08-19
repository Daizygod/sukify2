<script setup>
import { ref } from 'vue'
import Icon from '@/components/Icon.vue'
import ReleaseGrid from '@/components/ReleaseGrid.vue'

const sort = ref('added')
const SORTS = [
  { value: 'added', label: 'Недавно в Sukify' },
  { value: 'date', label: 'Дата выхода' },
  { value: 'name', label: 'По алфавиту' },
]
</script>

<template>
  <div class="content-pad">
    <div class="releases__head">
      <h1 class="releases__title">Все релизы</h1>
      <div class="releases__chips">
        <button
          v-for="s in SORTS"
          :key="s.value"
          class="chip"
          :class="{ 'chip--active': sort === s.value }"
          @click="sort = s.value"
        >
          {{ s.label }}
        </button>
      </div>
    </div>
    <ReleaseGrid :sort="sort" />
  </div>
</template>

<style scoped>
.releases__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 24px;
}
.releases__title {
  font-size: clamp(28px, 4vw, 40px);
  font-weight: 800;
}
.releases__chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
