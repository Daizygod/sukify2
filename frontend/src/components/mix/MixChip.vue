<script setup>
/**
 * Чип перехода между строками треклиста в режиме микса. Показывает, каким
 * пресетом склеена пара, и открывает редактор в правой панели.
 */
import { computed } from 'vue'
import Icon from '../Icon.vue'
import { useUiStore } from '@/stores/ui'
import { PRESETS } from '@/lib/mix/shapes'

const props = defineProps({
  from: { type: Object, required: true },
  to: { type: Object, required: true },
  // Сводка из пакетного запроса: { id, preset, bars, likes_count, count } | null
  info: { type: Object, default: null },
})

const ui = useUiStore()

const preset = computed(() => props.info?.preset || 'auto')
const title = computed(() => PRESETS[preset.value]?.title || 'Авто')
const active = computed(
  () =>
    ui.rightView === 'mix' &&
    ui.mixPair?.from?.id === props.from.id &&
    ui.mixPair?.to?.id === props.to.id
)
</script>

<template>
  <div class="mc">
    <button
      class="mc__btn"
      :class="{ 'mc__btn--on': active, 'mc__btn--custom': preset === 'custom' }"
      :title="`Переход: ${title}`"
      @click.stop="ui.openMixEditor(from, to)"
    >
      <Icon :name="preset === 'custom' ? 'edit' : 'sparkle'" :size="11" />
      <span>{{ title }}</span>
    </button>
  </div>
</template>

<style scoped>
.mc {
  display: flex;
  align-items: center;
  padding: 2px 0 2px 46px;
}
.mc__btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 4px;
  padding: 4px 9px;
}
.mc__btn:hover {
  background: rgba(255, 255, 255, 0.16);
}
.mc__btn--on {
  background: #fff;
  color: #000;
}
.mc__btn--custom :deep(svg) {
  color: var(--accent);
}
.mc__btn--on.mc__btn--custom :deep(svg) {
  color: #000;
}
/* На телефоне ряд узкий — чипы прячем, микс правится на десктопе. */
@media (max-width: 768px) {
  .mc {
    display: none;
  }
}
</style>
