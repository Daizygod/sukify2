<script setup>
import Icon from '../Icon.vue'
import { useDeviceStore } from '@/stores/devices'

const devices = useDeviceStore()
const emit = defineEmits(['close'])

function pick(d) {
  if (d.id !== devices.activeDeviceId) devices.transferTo(d.id)
  emit('close')
}
</script>

<template>
  <div class="sheet" @click.self="emit('close')">
    <div class="sheet__panel">
      <div class="sheet__grip"></div>
      <div class="sheet__head">
        <h3>Подключение к устройству</h3>
        <button class="sheet__close" @click="emit('close')">
          <Icon name="plus" :size="18" style="transform: rotate(45deg)" />
        </button>
      </div>

      <div class="sheet__scroll">
        <button
          v-for="d in devices.deviceList"
          :key="d.id"
          class="dev"
          :class="{ 'dev--active': d.id === devices.activeDeviceId }"
          @click="pick(d)"
        >
          <Icon :name="d.type === 'mobile' ? 'miniplayer' : 'devices'" :size="22" class="dev__icon" />
          <div class="dev__meta">
            <div class="dev__name">{{ d.name }}</div>
            <div v-if="d.id === devices.activeDeviceId" class="dev__now">
              <Icon name="volume" :size="12" />
              <span>Сейчас играет</span>
            </div>
          </div>
        </button>

        <p v-if="devices.deviceList.length <= 1" class="sheet__hint">
          Открой Sukify на другом устройстве под этим же аккаунтом — оно появится здесь.
        </p>
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
  max-height: 70vh;
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
  padding: 0 12px 24px;
}
.dev {
  display: flex;
  align-items: center;
  gap: 14px;
  width: 100%;
  text-align: left;
  padding: 12px 8px;
  border-radius: 8px;
  color: #fff;
}
.dev:active {
  background: rgba(255, 255, 255, 0.08);
}
.dev__icon {
  color: var(--text-subdued);
  flex: 0 0 22px;
}
.dev--active .dev__icon,
.dev--active .dev__name {
  color: var(--accent);
}
.dev__name {
  font-size: 16px;
  font-weight: 600;
}
.dev__now {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--accent);
  font-size: 12px;
  margin-top: 2px;
}
.sheet__hint {
  color: var(--text-subdued);
  font-size: 14px;
  padding: 12px 8px 20px;
  line-height: 1.5;
}
</style>
