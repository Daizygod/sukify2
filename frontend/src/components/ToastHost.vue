<script setup>
import { useToastStore } from '@/stores/toasts'

const toasts = useToastStore()
</script>

<template>
  <div class="toasts">
    <TransitionGroup name="toast">
      <div v-for="t in toasts.items" :key="t.id" class="toast">{{ t.text }}</div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toasts {
  position: fixed;
  bottom: calc(var(--player-height) + 16px);
  left: 50%;
  translate: -50% 0;
  z-index: 100;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
}
.toast {
  background: #fff;
  color: #000;
  font-size: 14px;
  font-weight: 500;
  border-radius: 8px;
  padding: 12px 16px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.5);
  white-space: nowrap;
}
.toast-enter-active {
  transition: opacity 0.22s ease, transform 0.38s var(--ease-spotify);
}
.toast-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.toast-enter-from {
  opacity: 0;
  transform: translateY(16px) scale(0.94);
}
.toast-leave-to {
  opacity: 0;
  transform: translateY(6px) scale(0.98);
}
/* Соседние тосты расходятся плавно, а не прыжком. */
.toast-move {
  transition: transform 0.3s var(--ease-spotify);
}
</style>
