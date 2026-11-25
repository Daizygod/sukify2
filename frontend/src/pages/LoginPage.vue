<script setup>
import { ref } from 'vue'
import { useRouter, useRoute, RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useLibraryStore } from '@/stores/library'
import { usePlayerStore } from '@/stores/player'

const auth = useAuthStore()
const library = useLibraryStore()
const player = usePlayerStore()
const router = useRouter()
const route = useRoute()

const form = ref({ email: '', password: '' })
const error = ref('')
const busy = ref(false)

async function submit() {
  error.value = ''
  busy.value = true
  try {
    await auth.login(form.value)
    await Promise.all([library.load().catch(() => {}), player.loadSettings()])
    router.push(route.query.redirect || '/')
  } catch (e) {
    error.value = e.response?.data?.message || 'Something went wrong.'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="auth">
    <div class="auth__card">
      <div class="auth__logo">🎧 Sukify</div>
      <h1 class="auth__title">Войти в Sukify</h1>

      <form @submit.prevent="submit" class="auth__form">
        <label>
          <span>Электронная почта</span>
          <input v-model="form.email" type="email" required />
        </label>
        <label>
          <span>Пароль</span>
          <input v-model="form.password" type="password" required />
        </label>

        <p v-if="error" class="auth__error">{{ error }}</p>

        <button class="btn-primary auth__submit" :disabled="busy">
          {{ busy ? '…' : 'Войти' }}
        </button>
      </form>

      <p class="auth__note">Регистрации нет: аккаунт выдаёт администратор.</p>
    </div>
  </div>
</template>

<style scoped>
.auth {
  min-height: 100%;
  display: grid;
  place-items: center;
  background: linear-gradient(180deg, #1f1f1f, #121212);
  padding: 40px 16px;
}
.auth__card {
  background: #121212;
  border-radius: 12px;
  padding: 40px;
  width: 100%;
  max-width: 420px;
  text-align: center;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.5);
}
.auth__logo {
  font-size: 22px;
  font-weight: 800;
  margin-bottom: 24px;
}
.auth__title {
  font-size: 28px;
  font-weight: 800;
  margin-bottom: 28px;
}
.auth__form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  text-align: left;
}
.auth__form label span {
  display: block;
  font-size: 14px;
  font-weight: 700;
  margin-bottom: 6px;
}
.auth__form input {
  width: 100%;
  background: #121212;
  border: 1px solid #727272;
  border-radius: 4px;
  padding: 12px 14px;
  color: #fff;
  font-size: 16px;
}
.auth__form input:focus {
  outline: none;
  border-color: #fff;
}
.auth__error {
  color: #f15e6c;
  font-size: 14px;
}
.auth__submit {
  margin-top: 8px;
  width: 100%;
}
.auth__note {
  margin-top: 24px;
  color: var(--text-subdued);
  font-size: 14px;
  border-top: 1px solid #292929;
  padding-top: 24px;
}
</style>
