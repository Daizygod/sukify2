<script setup>
import { useRoute, RouterLink } from 'vue-router'
import Icon from '../Icon.vue'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const ui = useUiStore()

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
      <Icon :name="isOn(i) && i.iconActive ? i.iconActive : i.icon" :size="26" />
      <span>{{ i.label }}</span>
    </RouterLink>
    <!-- Четвёртая вкладка, как в приложении Spotify: «Создать» -->
    <button class="mnav__item" @click="ui.createPlaylistOpen = true">
      <Icon name="plus" :size="26" />
      <span>Создать</span>
    </button>
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
  /* Полупрозрачный градиент, как в приложении: контент виден сквозь затемнение. */
  background: linear-gradient(180deg, rgba(0, 0, 0, 0) 0%, rgba(0, 0, 0, 0.62) 34%, rgba(0, 0, 0, 0.92) 100%);
}
.mnav__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  /* Замерено по скриншоту приложения: подпись крупнее и ниже иконки. */
  gap: 6px;
  padding: 8px 0 10px;
  color: var(--text-subdued);
  font-size: 12px;
  font-weight: 500;
  background: transparent;
  white-space: nowrap;
}
.mnav__item.on {
  color: #fff;
}
</style>
