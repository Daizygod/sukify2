<script setup>
import Icon from './Icon.vue'

/** Белая «пилюля» «Синхронизация» — появляется, когда текст увели руками. */
defineProps({ show: { type: Boolean, default: false } })
defineEmits(['sync'])
</script>

<template>
  <Transition name="lysync">
    <button v-if="show" class="lysync" type="button" @click="$emit('sync')">
      <Icon name="sync" :size="16" />
      <span>Синхронизация</span>
    </button>
  </Transition>
</template>

<style scoped>
.lysync {
  position: absolute;
  left: 50%;
  bottom: 24px;
  translate: -50% 0;
  z-index: 4;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 48px;
  padding: 0 24px;
  border-radius: 999px;
  background: #fff;
  color: #000;
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35);
  transition: transform 0.15s cubic-bezier(0.3, 0, 0, 1), background-color 0.15s ease;
}
.lysync:hover {
  transform: scale(1.04);
  background: #f2f2f2;
}
.lysync:active {
  transform: scale(0.97);
}
.lysync-enter-active,
.lysync-leave-active {
  transition: opacity 0.22s ease, translate 0.32s cubic-bezier(0.3, 0, 0, 1);
}
.lysync-enter-from,
.lysync-leave-to {
  opacity: 0;
  translate: -50% 18px;
}
@media (max-width: 768px) {
  .lysync {
    height: 40px;
    padding: 0 18px;
    font-size: 14px;
    bottom: calc(20px + env(safe-area-inset-bottom, 0px));
  }
}
</style>
