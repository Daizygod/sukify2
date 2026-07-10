<script setup>
import { ref, watch } from 'vue'
import { RouterLink, useRouter, useRoute } from 'vue-router'
import Icon from './Icon.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const menuOpen = ref(false)
const q = ref(route.params.q || '')

let t
watch(q, (v) => {
  clearTimeout(t)
  t = setTimeout(() => {
    if (v) router.push(`/search/${encodeURIComponent(v)}`)
    else if (route.name?.toString().startsWith('search')) router.push('/search')
  }, 200)
})

async function logout() {
  await auth.logout()
  menuOpen.value = false
  router.push('/')
}
</script>

<template>
  <header class="topbar">
    <div class="topbar__left">
      <div class="topbar__logo">🎧</div>
    </div>

    <div class="topbar__center">
      <RouterLink to="/" class="topbar__home" :class="{ active: route.name === 'home' }" aria-label="Главная">
        <Icon name="home" :size="24" />
      </RouterLink>
      <div class="topbar__search">
        <Icon name="search" :size="20" class="topbar__searchicon" />
        <input v-model="q" class="topbar__input" placeholder="Что хочешь включить?" />
        <div class="topbar__divider"></div>
        <RouterLink to="/search" class="topbar__browse" title="Обзор">
          <Icon name="browse" :size="20" />
        </RouterLink>
      </div>
    </div>

    <div class="topbar__right">
      <template v-if="auth.isAuthenticated">
        <button class="topbar__install">
          <Icon name="downloadCircle" :size="16" />
          <span>Установить приложение</span>
        </button>
        <button class="topbar__ghost" title="Что нового">
          <Icon name="bell" :size="16" />
        </button>
        <button class="topbar__ghost" title="Активность друзей">
          <Icon name="friends" :size="16" />
        </button>
        <RouterLink v-if="auth.isAdmin" to="/admin" class="topbar__link">Админ</RouterLink>
        <div class="topbar__user" @click="menuOpen = !menuOpen">
          <div class="topbar__avatar">{{ (auth.user.name || '?')[0].toUpperCase() }}</div>
          <div v-if="menuOpen" class="topbar__menu" @click.stop>
            <RouterLink :to="{ name: 'profile', params: { username: auth.user.username || auth.user.id } }" class="topbar__mi" @click="menuOpen=false">Профиль</RouterLink>
            <button class="topbar__mi" @click="logout">Выйти</button>
          </div>
        </div>
      </template>
      <template v-else>
        <RouterLink to="/login" class="topbar__link">Зарегистрироваться</RouterLink>
        <RouterLink to="/login" class="btn-primary topbar__login">Войти</RouterLink>
      </template>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  height: var(--topbar-height);
  display: grid;
  grid-template-columns: var(--sidebar-width) 1fr auto;
  align-items: center;
  gap: 8px;
}
.topbar__left {
  display: flex;
  align-items: center;
  padding-left: 8px;
}
.topbar__logo {
  font-size: 28px;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
}
.topbar__center {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: flex-start;
}
.topbar__home {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--bg-elevated);
  color: var(--text-subdued);
  display: grid;
  place-items: center;
  flex: 0 0 48px;
}
.topbar__home:hover {
  color: #fff;
  transform: scale(1.04);
}
.topbar__home.active {
  color: #fff;
}
.topbar__search {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--bg-elevated);
  border: 1px solid transparent;
  border-radius: 999px;
  padding: 0 6px 0 16px;
  height: 48px;
  width: 100%;
  max-width: 474px;
}
.topbar__search:hover {
  background: #2a2a2a;
}
.topbar__search:focus-within {
  border-color: #fff;
  background: #2a2a2a;
}
.topbar__searchicon {
  color: var(--text-subdued);
}
.topbar__input {
  flex: 1;
  background: none;
  border: none;
  color: #fff;
  font-size: 14px;
  outline: none;
  min-width: 0;
}
.topbar__input::placeholder {
  color: var(--text-subdued);
}
.topbar__divider {
  width: 1px;
  height: 24px;
  background: #7c7c7c;
  flex: 0 0 1px;
}
.topbar__browse {
  color: var(--text-subdued);
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 50%;
}
.topbar__browse:hover {
  color: #fff;
}
.topbar__right {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-right: 12px;
  justify-content: flex-end;
}
.topbar__install {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-subdued);
  font-size: 13px;
  font-weight: 700;
  padding: 8px 12px;
  border-radius: 999px;
}
.topbar__install:hover {
  color: #fff;
  transform: scale(1.03);
}
.topbar__ghost {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  color: var(--text-subdued);
  border-radius: 50%;
}
.topbar__ghost:hover {
  color: #fff;
  transform: scale(1.05);
}
.topbar__link {
  color: var(--text-subdued);
  font-weight: 700;
  font-size: 14px;
  padding: 0 8px;
}
.topbar__link:hover {
  color: #fff;
}
.topbar__user {
  position: relative;
  cursor: pointer;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--bg-elevated);
  display: grid;
  place-items: center;
}
.topbar__avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #19a34a;
  color: #000;
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 14px;
}
.topbar__menu {
  position: absolute;
  right: 0;
  top: 52px;
  background: #282828;
  border-radius: 4px;
  min-width: 180px;
  padding: 4px;
  box-shadow: 0 16px 24px rgba(0, 0, 0, 0.4);
  z-index: 30;
}
.topbar__mi {
  display: block;
  width: 100%;
  text-align: left;
  padding: 12px;
  color: #fff;
  font-size: 14px;
  border-radius: 2px;
}
.topbar__mi:hover {
  background: rgba(255, 255, 255, 0.1);
}
</style>
