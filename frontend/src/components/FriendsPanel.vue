<script setup>
import Icon from './Icon.vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
</script>

<template>
  <aside class="fp">
    <div class="fp__head">
      <span class="fp__title">Активность друзей</span>
      <button class="fp__close" title="Закрыть" @click="ui.closeRight()">
        <Icon name="plus" :size="16" style="transform: rotate(45deg)" />
      </button>
    </div>
    <div class="fp__body">
      <p class="fp__lead">Смотри, что слушают друзья.</p>
      <p class="muted fp__text">
        Подпишись на друзей через их профиль — и здесь в реальном времени будет
        видно, какие треки они включают.
      </p>
    </div>
  </aside>
</template>

<style scoped>
.fp {
  height: 100%;
  background: var(--bg-elevated);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.fp__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 16px 8px;
}
.fp__title {
  font-weight: 700;
  font-size: 15px;
}
.fp__close {
  color: var(--text-subdued);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
}
.fp__close:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.fp__body {
  padding: 16px;
  overflow-y: auto;
}
.fp__lead {
  font-weight: 700;
  margin-bottom: 8px;
}
.fp__text {
  font-size: 13px;
  line-height: 1.5;
}
</style>
