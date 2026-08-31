<script setup>
/**
 * Чип перехода между строками треклиста — единственная точка входа в
 * редактор. В режиме микса висит всегда и подписан пресетом, в обычном
 * списке проявляется по наведению, как раньше делала точка перехода.
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
  // Режим микса: чип видно всегда, а не только под курсором.
  dense: { type: Boolean, default: false },
})

const ui = useUiStore()

const preset = computed(() => props.info?.preset || 'auto')
// Без своего перехода в режиме микса пишем «Авто»: он там и правда играет
// автоматический. В обычном списке чипа не видно, пока не наведёшь, и слово
// «Переход» объясняет, что это вообще такое.
const title = computed(() => {
  if (props.info) return PRESETS[preset.value]?.title || 'Свой вариант'

  return props.dense ? PRESETS.auto.title : 'Переход'
})
const active = computed(
  () =>
    ui.rightView === 'mix' &&
    ui.mixPair?.from?.id === props.from.id &&
    ui.mixPair?.to?.id === props.to.id
)
</script>

<template>
  <div class="mc" :class="{ 'mc--dense': dense, 'mc--has': info }">
    <button
      class="mc__btn"
      :class="{ 'mc__btn--on': active, 'mc__btn--custom': preset === 'custom' }"
      :title="`Переход: ${title}`"
      @click.stop="ui.openMixEditor(from, to)"
    >
      <Icon :name="preset === 'custom' ? 'edit' : 'sparkle'" :size="11" />
      <span>{{ title }}</span>
      <span v-if="info?.likes_count" class="mc__likes">♥ {{ info.likes_count }}</span>
    </button>
  </div>
</template>

<style scoped>
/* Обычный список: полоска в четыре пикселя, кнопка выезжает по наведению —
   иначе между каждой парой строк торчала бы плашка. */
.mc {
  position: relative;
  height: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2;
}
.mc__btn {
  display: inline-flex;
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
.mc:hover .mc__btn,
.mc--has .mc__btn {
  opacity: 1;
  transform: scale(1);
}
.mc--has .mc__btn {
  color: var(--accent);
  border-color: rgba(30, 215, 96, 0.35);
}
.mc__btn:hover {
  color: #fff;
  border-color: rgba(255, 255, 255, 0.4);
}
.mc__likes {
  color: var(--text-subdued);
}

/* Режим микса: чип стоит в потоке под строкой и подписан пресетом. */
.mc--dense {
  height: auto;
  justify-content: flex-start;
  padding: 2px 0 2px 46px;
}
.mc--dense .mc__btn {
  opacity: 1;
  transform: none;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
  border: 0;
  border-radius: 4px;
  padding: 4px 9px;
}
.mc--dense .mc__btn:hover {
  background: rgba(255, 255, 255, 0.16);
}
.mc--dense .mc__btn--on {
  background: #fff;
  color: #000;
}
.mc--dense .mc__btn--custom :deep(svg) {
  color: var(--accent);
}
.mc--dense .mc__btn--on.mc__btn--custom :deep(svg) {
  color: #000;
}
/* На телефоне ряд узкий и наведения нет — переходы правятся на десктопе. */
@media (max-width: 768px) {
  .mc {
    display: none;
  }
}
</style>
