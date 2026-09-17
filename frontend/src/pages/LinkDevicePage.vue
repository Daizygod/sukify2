<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/lib/api'
import { useToastStore } from '@/stores/toasts'

const route = useRoute()
const toasts = useToastStore()

// Код приезжает из ссылки, которую открыл компаньон; руками его вводят только
// если браузер открылся без параметров (например, на другой машине).
const code = ref(String(route.query.code || '').toUpperCase())
const device = ref(null)
const state = ref('idle') // idle | checking | found | approved | error
const error = ref('')

function normalize(raw) {
  const clean = raw.toUpperCase().replace(/[^A-Z0-9]/g, '')
  return clean.length > 4 ? `${clean.slice(0, 4)}-${clean.slice(4, 8)}` : clean
}

function onInput(e) {
  code.value = normalize(e.target.value)
}

async function lookup() {
  if (code.value.length < 9) return
  state.value = 'checking'
  error.value = ''
  try {
    const { data } = await api.get(`/desktop/pending/${code.value}`)
    device.value = data.data
    state.value = 'found'
  } catch {
    state.value = 'error'
    error.value = 'Код не найден или истёк. Запроси новый в приложении.'
  }
}

async function approve() {
  try {
    await api.post('/desktop/approve', { user_code: code.value })
    state.value = 'approved'
    toasts.show('Приложение подключено')
  } catch {
    state.value = 'error'
    error.value = 'Не удалось подтвердить — код мог истечь.'
  }
}

onMounted(() => {
  if (code.value.length >= 9) lookup()
})
</script>

<template>
  <div class="content-pad link">
    <div class="link__card">
      <div class="link__logo">🎧</div>

      <template v-if="state === 'approved'">
        <h1>Готово</h1>
        <p class="link__lead">
          Приложение подключено к твоему аккаунту. Можешь вернуться в него —
          статус в Discord появится, как только заиграет музыка.
        </p>
        <RouterLink class="link__btn link__btn--primary" :to="{ name: 'settings' }">
          В настройки
        </RouterLink>
      </template>

      <template v-else-if="state === 'found'">
        <h1>Подключить приложение?</h1>
        <p class="link__lead">
          Оно сможет читать, что играет, и показывать это в статусе Discord.
        </p>
        <div class="link__device">
          <div class="link__device-name">{{ device.name }}</div>
          <div class="link__device-meta" v-if="device.platform">{{ device.platform }}</div>
        </div>
        <div class="link__code">{{ code }}</div>
        <p class="link__warn">
          Подтверждай, только если этот код прямо сейчас показывает приложение
          на твоём компьютере.
        </p>
        <div class="link__actions">
          <button class="link__btn link__btn--primary" @click="approve">Подключить</button>
          <RouterLink class="link__btn" :to="{ name: 'home' }">Отмена</RouterLink>
        </div>
      </template>

      <template v-else>
        <h1>Подключение приложения</h1>
        <p class="link__lead">Введи код, который показывает Sukify на компьютере.</p>
        <input
          class="link__input"
          :value="code"
          maxlength="9"
          placeholder="XXXX-XXXX"
          autocomplete="off"
          spellcheck="false"
          @input="onInput"
          @keyup.enter="lookup"
        />
        <p v-if="error" class="link__error">{{ error }}</p>
        <button
          class="link__btn link__btn--primary"
          :disabled="code.length < 9 || state === 'checking'"
          @click="lookup"
        >
          {{ state === 'checking' ? 'Проверяем…' : 'Продолжить' }}
        </button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.link {
  display: grid;
  place-items: center;
  min-height: 70vh;
}
.link__card {
  width: 100%;
  max-width: 420px;
  text-align: center;
  background: #181818;
  border-radius: 12px;
  padding: 32px 28px;
}
.link__logo {
  font-size: 40px;
  margin-bottom: 12px;
}
.link__card h1 {
  font-size: 24px;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin-bottom: 8px;
}
.link__lead {
  color: var(--text-subdued);
  font-size: 14px;
  line-height: 1.5;
  margin-bottom: 20px;
}
.link__device {
  background: #2a2a2a;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 16px;
}
.link__device-name {
  font-weight: 700;
}
.link__device-meta {
  color: var(--text-subdued);
  font-size: 13px;
  margin-top: 2px;
}
.link__code {
  font-size: 28px;
  font-weight: 800;
  letter-spacing: 0.18em;
  font-variant-numeric: tabular-nums;
  margin-bottom: 16px;
}
.link__warn {
  color: var(--text-subdued);
  font-size: 13px;
  line-height: 1.5;
  margin-bottom: 20px;
}
.link__error {
  color: #f15e6c;
  font-size: 13px;
  margin-bottom: 12px;
}
.link__input {
  background: #2a2a2a;
  border: 1px solid transparent;
  border-radius: 6px;
  color: #fff;
  font-family: inherit;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-align: center;
  padding: 12px;
  width: 100%;
  margin-bottom: 16px;
}
.link__input:focus {
  border-color: #727272;
  outline: none;
}
.link__actions {
  display: flex;
  gap: 10px;
  justify-content: center;
}
.link__btn {
  display: inline-block;
  border: 1px solid var(--text-muted);
  color: #fff;
  border-radius: 999px;
  padding: 10px 22px;
  font-size: 14px;
  font-weight: 700;
}
.link__btn:hover:not(:disabled) {
  border-color: #fff;
}
.link__btn--primary {
  background: #fff;
  color: #000;
  border-color: #fff;
}
.link__btn:disabled {
  opacity: 0.6;
}
</style>
