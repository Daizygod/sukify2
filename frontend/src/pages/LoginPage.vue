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

const mode = ref('login') // login | register
const form = ref({ name: '', email: '', password: '', password_confirmation: '' })
const error = ref('')
const busy = ref(false)

async function submit() {
  error.value = ''
  busy.value = true
  try {
    if (mode.value === 'login') {
      await auth.login({ email: form.value.email, password: form.value.password })
    } else {
      await auth.register(form.value)
    }
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
      <h1 class="auth__title">{{ mode === 'login' ? 'Log in to Sukify' : 'Sign up for Sukify' }}</h1>

      <form @submit.prevent="submit" class="auth__form">
        <label v-if="mode === 'register'">
          <span>Name</span>
          <input v-model="form.name" required />
        </label>
        <label>
          <span>Email</span>
          <input v-model="form.email" type="email" required />
        </label>
        <label>
          <span>Password</span>
          <input v-model="form.password" type="password" required />
        </label>
        <label v-if="mode === 'register'">
          <span>Confirm password</span>
          <input v-model="form.password_confirmation" type="password" required />
        </label>

        <p v-if="error" class="auth__error">{{ error }}</p>

        <button class="btn-primary auth__submit" :disabled="busy">
          {{ busy ? '…' : mode === 'login' ? 'Log In' : 'Sign Up' }}
        </button>
      </form>

      <p class="auth__hint" v-if="mode === 'login'">
        Demo: <code>demo@sukify.test</code> / <code>password</code>
      </p>

      <div class="auth__switch">
        <template v-if="mode === 'login'">
          Don't have an account?
          <button @click="mode = 'register'">Sign up</button>
        </template>
        <template v-else>
          Already have an account?
          <button @click="mode = 'login'">Log in</button>
        </template>
      </div>
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
  font-size: 13px;
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
  font-size: 15px;
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
.auth__hint {
  margin-top: 16px;
  font-size: 12px;
  color: var(--text-subdued);
}
.auth__switch {
  margin-top: 24px;
  color: var(--text-subdued);
  font-size: 14px;
  border-top: 1px solid #292929;
  padding-top: 24px;
}
.auth__switch button {
  color: #fff;
  text-decoration: underline;
  font-weight: 600;
}
</style>
