<script setup>
import { RouterLink } from 'vue-router'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'

defineProps({
  to: { type: [Object, String], required: true },
  cover: { type: Object, default: null },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  round: { type: Boolean, default: false },
})
const emit = defineEmits(['play'])
</script>

<template>
  <RouterLink :to="to" class="card">
    <div class="card__art">
      <CoverImage :cover="cover" :size="300" :rounded="round" :alt="title" />
      <button class="play-btn card__play" @click.prevent="emit('play')">
        <Icon name="play" :size="20" />
      </button>
    </div>
    <div class="card__title">{{ title }}</div>
    <div v-if="subtitle" class="card__subtitle">{{ subtitle }}</div>
  </RouterLink>
</template>

<style scoped>
.card {
  display: block;
  background: var(--bg-card);
  border-radius: var(--radius);
  padding: 16px;
  transition: background 0.25s ease;
}
.card:hover {
  background: var(--bg-card-hover);
}
.card__art {
  position: relative;
  margin-bottom: 16px;
}
.card__play {
  position: absolute;
  right: 8px;
  bottom: 8px;
  opacity: 0;
  transform: translateY(8px);
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.card:hover .card__play {
  opacity: 1;
  transform: translateY(0);
}
.card__title {
  font-weight: 700;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.card__subtitle {
  color: var(--text-subdued);
  font-size: 13px;
  margin-top: 4px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
