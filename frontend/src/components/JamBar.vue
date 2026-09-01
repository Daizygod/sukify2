<script setup>
/**
 * Зелёная полоса джема под плеером — как в оригинале: во всю ширину окна,
 * справа аватар хоста и подпись. Клик открывает панель подключения, где
 * лежат код, участники и выход.
 */
import { computed } from 'vue'
import { useJamStore } from '@/stores/jam'
import { useUiStore } from '@/stores/ui'

const jam = useJamStore()
const ui = useUiStore()

// Подпись одна и для хоста, и для гостя — как в оригинале: там хост тоже
// видит «Джем пользователя <своё имя>».
const label = computed(() => `Джем пользователя ${jam.host?.name || jam.host?.username || '—'}`)
const letter = computed(() => (jam.host?.name || '?')[0].toUpperCase())
</script>

<template>
  <button class="jb" :class="{ 'jb--off': !jam.connected }" @click="ui.openRight('connect')">
    <span class="jb__side">
      <span class="jb__avatar">
        <img v-if="jam.host?.avatar_url" :src="jam.host.avatar_url" alt="" />
        <span v-else>{{ letter }}</span>
      </span>
      <span class="jb__text">{{ label }}</span>
      <span v-if="!jam.connected" class="jb__warn">· нет связи</span>
    </span>
  </button>
</template>

<style scoped>
.jb {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  width: 100%;
  height: 24px;
  padding: 0 12px;
  border-radius: 6px;
  background: var(--accent);
  color: #000;
  font-size: 12px;
  font-weight: 700;
}
/* Связь отвалилась — полоса гаснет, но не исчезает: джем-то ещё идёт. */
.jb--off {
  background: #147a37;
  color: rgba(255, 255, 255, 0.85);
}
.jb__side {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.jb__avatar {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  overflow: hidden;
  background: #121212;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  display: grid;
  place-items: center;
  flex: none;
}
.jb__avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.jb__text {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.jb__warn {
  font-weight: 600;
  opacity: 0.85;
}
</style>
