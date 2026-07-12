<script setup>
import Icon from '../Icon.vue'
import CoverImage from '../CoverImage.vue'
import { usePlayerStore } from '@/stores/player'
import { useDeviceStore } from '@/stores/devices'

const player = usePlayerStore()
const devices = useDeviceStore()
const emit = defineEmits(['close'])

const names = (t) => (t.artists || []).map((a) => a.name).join(', ')
</script>

<template>
  <div class="sheet" @click.self="emit('close')">
    <div class="sheet__panel">
      <div class="sheet__grip"></div>
      <div class="sheet__head">
        <h3>Очередь</h3>
        <button class="sheet__close" @click="emit('close')">
          <Icon name="plus" :size="18" style="transform: rotate(45deg)" />
        </button>
      </div>

      <div class="sheet__scroll">
        <p v-if="devices.isRemote" class="sheet__hint">
          Музыка играет на «{{ devices.activeDevice?.name || 'другом устройстве' }}» — очередь
          смотри там или перенеси воспроизведение сюда.
        </p>

        <template v-else>
          <section v-if="player.currentTrack" class="sheet__section">
            <h4>Сейчас играет</h4>
            <div class="qrow">
              <CoverImage :cover="player.currentTrack.cover" :size="48" class="qrow__cover" />
              <div class="qrow__meta">
                <div class="qrow__title qrow__title--green">{{ player.currentTrack.title }}</div>
                <div class="qrow__artists">{{ names(player.currentTrack) }}</div>
              </div>
            </div>
          </section>

          <section v-if="player.manualQueue.length" class="sheet__section">
            <div class="sheet__sechead">
              <h4>Дальше в очереди</h4>
              <button class="sheet__clear" @click="player.clearManualQueue()">Очистить</button>
            </div>
            <div v-for="m in player.manualQueue" :key="m.__qid" class="qrow" @click="player.playManualItem(m.__qid)">
              <CoverImage :cover="m.cover" :size="48" class="qrow__cover" />
              <div class="qrow__meta">
                <div class="qrow__title">{{ m.title }}</div>
                <div class="qrow__artists">{{ names(m) }}</div>
              </div>
              <button class="qrow__x" @click.stop="player.removeFromManualQueue(m.__qid)">
                <Icon name="plus" :size="14" style="transform: rotate(45deg)" />
              </button>
            </div>
          </section>

          <section v-if="player.upcoming.length" class="sheet__section">
            <h4>Далее: {{ player.contextName || 'текущий список' }}</h4>
            <div v-for="(t, i) in player.upcoming.slice(0, 50)" :key="t.id + '-' + i" class="qrow" @click="player.playUpcomingItem(i)">
              <CoverImage :cover="t.cover" :size="48" class="qrow__cover" />
              <div class="qrow__meta">
                <div class="qrow__title">{{ t.title }}</div>
                <div class="qrow__artists">{{ names(t) }}</div>
              </div>
            </div>
          </section>

          <p v-if="!player.manualQueue.length && !player.upcoming.length && !player.currentTrack" class="sheet__hint">
            Очередь пуста.
          </p>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sheet {
  position: fixed;
  inset: 0;
  z-index: 90;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: flex-end;
}
.sheet__panel {
  width: 100%;
  max-height: 78vh;
  background: #1c1c1c;
  border-radius: 16px 16px 0 0;
  display: flex;
  flex-direction: column;
  padding-bottom: env(safe-area-inset-bottom, 0);
}
.sheet__grip {
  width: 40px;
  height: 4px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.25);
  margin: 10px auto 2px;
}
.sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 20px;
}
.sheet__head h3 {
  font-size: 18px;
  font-weight: 700;
}
.sheet__close {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  color: var(--text-subdued);
}
.sheet__scroll {
  overflow-y: auto;
  min-height: 0;
  padding: 0 20px 24px;
}
.sheet__section {
  margin-bottom: 18px;
}
.sheet__section h4 {
  font-size: 14px;
  font-weight: 700;
  margin-bottom: 8px;
}
.sheet__sechead {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.sheet__clear {
  color: var(--text-subdued);
  font-size: 13px;
  font-weight: 700;
}
.sheet__hint {
  color: var(--text-subdued);
  font-size: 14px;
  padding: 12px 0 20px;
  line-height: 1.5;
}
.qrow {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 0;
}
.qrow__cover {
  width: 44px;
  flex: 0 0 44px;
  border-radius: 4px;
}
.qrow__meta {
  flex: 1;
  min-width: 0;
}
.qrow__title {
  color: #fff;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.qrow__title--green {
  color: var(--accent);
}
.qrow__artists {
  color: var(--text-subdued);
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.qrow__x {
  color: var(--text-subdued);
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
}
</style>
