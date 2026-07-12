<script setup>
import { useRoute, RouterLink } from 'vue-router'
import Icon from '../Icon.vue'

const route = useRoute()

const items = [
  { to: '/', label: 'Главная', icon: 'home', iconActive: 'homeFill', match: ['home', 'section', 'genre', 'mix'] },
  { to: '/search', label: 'Поиск', icon: 'search', match: ['search', 'search-query'] },
  { to: '/library', label: 'Моя медиатека', icon: 'library', match: ['library', 'liked', 'history', 'stats'] },
]

const isOn = (i) => i.match.includes(route.name)
</script>

<template>
  <nav class="mnav">
    <RouterLink v-for="i in items" :key="i.to" :to="i.to" class="mnav__item" :class="{ on: isOn(i) }">
      <Icon :name="isOn(i) && i.iconActive ? i.iconActive : i.icon" :size="24" />
      <span>{{ i.label }}</span>
    </RouterLink>
  </nav>
</template>

<style scoped>
.mnav {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 50;
  display: flex;
  padding-bottom: env(safe-area-inset-bottom, 0);
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.72), #000 46%);
}
.mnav__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 10px 0 8px;
  color: var(--text-subdued);
  font-size: 11px;
  font-weight: 500;
}
.mnav__item.on {
  color: #fff;
}
</style>
