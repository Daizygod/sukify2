<script setup>
import { ref } from 'vue'
import Icon from './Icon.vue'
import { usePlayerStore } from '@/stores/player'
import { useToastStore } from '@/stores/toasts'

const props = defineProps({
  tracks: { type: Array, default: () => [] },
  link: { type: String, default: '' },
  canDelete: { type: Boolean, default: false },
  canInvite: { type: Boolean, default: false },
})
const emit = defineEmits(['delete', 'invite'])

const player = usePlayerStore()
const toasts = useToastStore()
const open = ref(false)

function addAllToQueue() {
  let n = 0
  for (const t of props.tracks) if (player.addToQueue(t)) n++
  toasts.show(`В очередь добавлено: ${n}`)
  open.value = false
}

async function copyLink() {
  const url = props.link || location.href
  try {
    await navigator.clipboard.writeText(url)
    toasts.show('Ссылка скопирована в буфер обмена')
  } catch {
    toasts.show(url)
  }
  open.value = false
}
</script>

<template>
  <div class="hm">
    <button class="ctl-lg" title="Открыть контекстное меню" @click="open = !open">
      <Icon name="moreBig" :size="32" />
    </button>
    <Teleport to="body">
      <div v-if="open" class="hm__backdrop" @click="open = false"></div>
    </Teleport>
    <div v-if="open" class="hm__menu">
      <button class="hm__item" @click="addAllToQueue">
        <Icon name="queue" :size="16" />
        <span>Добавить в очередь</span>
      </button>
      <button class="hm__item" @click="copyLink">
        <Icon name="share" :size="16" />
        <span>Поделиться</span>
      </button>
      <button v-if="canInvite" class="hm__item" @click="open = false; emit('invite')">
        <Icon name="person" :size="16" />
        <span>Пригласить участников</span>
      </button>
      <template v-if="canDelete">
        <div class="hm__divider"></div>
        <button class="hm__item" @click="open = false; emit('delete')">
          <Icon name="plus" :size="16" style="transform: rotate(45deg)" />
          <span>Удалить плейлист</span>
        </button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.hm {
  position: relative;
  display: flex;
}
.hm__backdrop {
  position: fixed;
  inset: 0;
  z-index: 80;
}
.hm__menu {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  z-index: 85;
  min-width: 240px;
  background: #282828;
  border-radius: 4px;
  box-shadow: 0 16px 24px rgba(0, 0, 0, 0.4);
  padding: 4px;
}
.hm__item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  border-radius: 2px;
  color: #ffffffe6;
  font-size: 14px;
  text-align: left;
}
.hm__item:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}
.hm__item svg {
  color: var(--text-subdued);
}
.hm__divider {
  height: 1px;
  background: rgba(255, 255, 255, 0.1);
  margin: 4px 0;
}
.ctl-lg {
  color: var(--text-subdued);
  display: grid;
  place-items: center;
}
.ctl-lg:hover {
  color: #fff;
  transform: scale(1.04);
}
</style>
