<script setup>
import Icon from './Icon.vue'
import { useDeviceStore } from '@/stores/devices'
import { useUiStore } from '@/stores/ui'

const devices = useDeviceStore()
const ui = useUiStore()
</script>

<template>
  <aside class="cp">
    <div class="cp__head">
      <span class="cp__title">Подключение</span>
      <button class="cp__close" title="Закрыть" @click="ui.closeRight()">
        <Icon name="plus" :size="16" style="transform: rotate(45deg)" />
      </button>
    </div>

    <div class="cp__body">
      <button
        v-for="d in devices.deviceList"
        :key="d.id"
        class="cp__device"
        :class="{ 'cp__device--active': d.id === devices.activeDeviceId }"
        @click="devices.transferTo(d.id)"
      >
        <Icon :name="d.type === 'phone' ? 'miniplayer' : 'devices'" :size="18" class="cp__icon" />
        <div class="cp__meta">
          <span class="cp__name">{{ d.self ? 'Этот браузер' : d.name }}</span>
          <span v-if="d.self" class="cp__sub">{{ d.name }}</span>
        </div>
        <Icon
          v-if="d.id === devices.activeDeviceId"
          name="volume"
          :size="14"
          class="cp__playing"
        />
      </button>

      <p v-if="devices.deviceList.length <= 1" class="cp__hint muted">
        Открой Сукифай в другой вкладке или на другом устройстве под этим же
        аккаунтом — оно появится здесь, и музыку можно будет переключать между
        устройствами.
      </p>
    </div>

    <div class="cp__foot">
      <span>Твоего устройства здесь нет?</span>
      <Icon name="expand" :size="14" />
    </div>
  </aside>
</template>

<style scoped>
.cp {
  height: 100%;
  background: var(--bg-elevated);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.cp__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 16px 8px;
}
.cp__title {
  font-weight: 700;
  font-size: 15px;
}
.cp__close {
  color: var(--text-subdued);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
}
.cp__close:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.cp__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.cp__device {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 12px;
  border-radius: 8px;
  color: #fff;
  text-align: left;
  width: 100%;
}
.cp__device:hover {
  background: rgba(255, 255, 255, 0.08);
}
.cp__device--active {
  background: #2a2a2a;
}
.cp__icon {
  color: var(--text-subdued);
  flex: 0 0 auto;
}
.cp__device--active .cp__icon,
.cp__device--active .cp__name {
  color: var(--accent);
}
.cp__meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}
.cp__name {
  font-weight: 700;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cp__sub {
  color: var(--text-subdued);
  font-size: 12px;
}
.cp__playing {
  color: var(--accent);
  flex: 0 0 auto;
}
.cp__hint {
  font-size: 13px;
  padding: 16px 8px;
  line-height: 1.5;
}
.cp__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: space-between;
  padding: 16px;
  color: var(--text-subdued);
  font-size: 14px;
  font-weight: 700;
}
</style>
