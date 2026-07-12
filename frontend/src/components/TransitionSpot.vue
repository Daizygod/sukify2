<script setup>
import { ref } from 'vue'
import Icon from './Icon.vue'
import TransitionModal from './TransitionModal.vue'

const props = defineProps({
  from: { type: Object, required: true },
  to: { type: Object, required: true },
  // Best transition summary from the batch endpoint: {likes_count, count} | null
  info: { type: Object, default: null },
})
const emit = defineEmits(['changed'])

const open = ref(false)
</script>

<template>
  <div class="tsp" :class="{ 'tsp--has': info }">
    <button class="tsp__btn" title="Переход между треками" @click="open = true">
      <Icon name="shuffle" :size="11" />
      <span v-if="info">{{ info.count }} · ♥ {{ info.likes_count }}</span>
      <span v-else>Переход</span>
    </button>
  </div>

  <TransitionModal
    v-if="open"
    :from="from"
    :to="to"
    @close="open = false"
    @changed="emit('changed')"
  />
</template>

<style scoped>
.tsp {
  position: relative;
  height: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2;
}
.tsp__btn {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 700;
  color: var(--text-subdued);
  background: #1f1f1f;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 999px;
  padding: 2px 10px;
  opacity: 0;
  transform: scale(0.9);
  transition: opacity 0.15s ease, transform 0.15s ease, color 0.15s ease;
}
.tsp:hover .tsp__btn,
.tsp--has .tsp__btn {
  opacity: 1;
  transform: scale(1);
}
.tsp--has .tsp__btn {
  color: var(--accent);
  border-color: rgba(30, 215, 96, 0.35);
}
.tsp__btn:hover {
  color: #fff;
  border-color: rgba(255, 255, 255, 0.4);
}
/* На мобильном нет ховера — точки переходов не показываем. */
@media (max-width: 768px) {
  .tsp {
    display: none;
  }
}
</style>
