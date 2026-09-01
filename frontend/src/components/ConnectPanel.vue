<script setup>
import { ref } from 'vue'
import Icon from './Icon.vue'
import { useDeviceStore } from '@/stores/devices'
import { useUiStore } from '@/stores/ui'
import { useJamStore } from '@/stores/jam'
import { useToastStore } from '@/stores/toasts'

const devices = useDeviceStore()
const ui = useUiStore()
const jam = useJamStore()
const toasts = useToastStore()
const joinCode = ref('')

async function startJam() {
  try {
    await jam.start()
  } catch {
    toasts.show('Не получилось создать Jam')
  }
}

async function joinJam() {
  if (!joinCode.value.trim()) return
  try {
    await jam.join(joinCode.value)
    joinCode.value = ''
  } catch {
    toasts.show('Jam с таким кодом не найден')
  }
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(jam.session.join_code)
    toasts.show('Код скопирован — отправь его друзьям')
  } catch {
    toasts.show(jam.session.join_code)
  }
}
</script>

<template>
  <aside class="cp">
    <div class="cp__head">
      <span class="cp__title">Подключение</span>
      <button class="cp__close" title="Закрыть" @click="ui.closeRight()">
        <Icon name="plus" :size="16" style="transform: rotate(45deg)" />
      </button>
    </div>

    <div v-osbar class="cp__body">
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

    <div class="cp__jam">
      <div class="cp__jamhead">
        <Icon name="jam" :size="16" />
        <span>Джем — слушаем вместе</span>
      </div>

      <template v-if="jam.active">
        <div class="cp__jamcode" @click="copyCode">
          Код: <b>{{ jam.session.join_code }}</b>
          <Icon name="share" :size="14" />
        </div>
        <div class="cp__jamfaces">
          <span
            v-for="m in jam.members"
            :key="m.id"
            class="cp__face"
            :title="m.name"
            :class="{ 'cp__face--host': m.id === jam.host?.id }"
          >
            <img v-if="m.avatar_url" :src="m.avatar_url" alt="" />
            <span v-else>{{ (m.name || '?')[0].toUpperCase() }}</span>
          </span>
        </div>
        <div class="cp__jammembers muted">
          {{ jam.isHost ? 'Ты хост' : `Хост: ${jam.host?.name || '—'}` }} •
          участников: {{ jam.members.length }}
          <template v-if="!jam.connected"> • нет связи с сервером</template>
        </div>
        <div class="cp__jammembers muted">
          Переключать треки и ставить паузу может любой — у всех играет одно и то же.
        </div>
        <button class="cp__jambtn cp__jambtn--leave" @click="jam.leave()">
          {{ jam.isHost ? 'Закончить джем' : 'Выйти из джема' }}
        </button>
      </template>

      <template v-else>
        <button class="cp__jambtn" @click="startJam">Начать джем</button>
        <div class="cp__jamjoin">
          <input v-model="joinCode" placeholder="Код джема" maxlength="6" @keyup.enter="joinJam" />
          <button class="cp__jambtn" @click="joinJam">Войти</button>
        </div>
      </template>
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
  font-size: 16px;
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
  font-size: 14px;
  padding: 16px 8px;
  line-height: 1.5;
}
.cp__jam {
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.cp__jamhead {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  font-size: 14px;
}
.cp__jamcode {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #2a2a2a;
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 14px;
  cursor: pointer;
}
.cp__jamcode:hover {
  background: #333;
}
.cp__jammembers {
  font-size: 12px;
}
.cp__jamfaces {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}
.cp__face {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  overflow: hidden;
  background: #333;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  display: grid;
  place-items: center;
}
.cp__face img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
/* Хоста видно сразу — вокруг него зелёное кольцо. */
.cp__face--host {
  box-shadow: 0 0 0 2px var(--accent);
}
.cp__jambtn {
  background: var(--accent);
  color: #000;
  font-weight: 700;
  font-size: 14px;
  border-radius: 999px;
  padding: 8px 16px;
}
.cp__jambtn:hover {
  background: var(--accent-hover);
}
.cp__jambtn--leave {
  background: transparent;
  color: #fff;
  border: 1px solid var(--text-muted);
}
.cp__jambtn--leave:hover {
  background: transparent;
  border-color: #fff;
}
.cp__jamjoin {
  display: flex;
  gap: 8px;
}
.cp__jamjoin input {
  flex: 1;
  min-width: 0;
  background: #2a2a2a;
  border: 1px solid transparent;
  border-radius: 999px;
  color: #fff;
  padding: 8px 14px;
  font-size: 14px;
  outline: none;
  text-transform: uppercase;
}
.cp__jamjoin input:focus {
  border-color: #fff;
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
