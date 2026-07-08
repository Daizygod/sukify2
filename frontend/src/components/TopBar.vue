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
      <RouterLink to="/" class="topbar__home" :class="{ active: route.name === 'home' }" aria-label="Home">
        <Icon name="home" :size="24" />
      </RouterLink>
      <div class="topbar__search">
        <Icon name="search" :size="20" class="topbar__searchicon" />
        <input v-model="q" class="topbar__input" placeholder="What do you want to play?" />
      </div>
    </div>

    <div class="topbar__right">
      <template v-if="auth.isAuthenticated">
        <RouterLink v-if="auth.isAdmin" to="/admin" class="topbar__link">Admin</RouterLink>
        <div class="topbar__user" @click="menuOpen = !menuOpen">
          <div class="topbar__avatar">{{ (auth.user.name || '?')[0].toUpperCase() }}</div>
          <div v-if="menuOpen" class="topbar__menu" @click.stop>
            <RouterLink :to="{ name: 'profile', params: { username: auth.user.username || auth.user.id } }" class="topbar__mi" @click="menuOpen=false">Profile</RouterLink>
            <button class="topbar__mi" @click="logout">Log out</button>
          </div>
        </div>
      </template>
      <template v-else>
        <RouterLink to="/login" class="topbar__link">Sign up</RouterLink>
        <RouterLink to="/login" class="btn-primary topbar__login">Log in</RouterLink>
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
  padding: 0 16px;
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
}
.topbar__input::placeholder {
  color: var(--text-subdued);
}
.topbar__right {
  display: flex;
  align-items: center;
  gap: 16px;
  padding-right: 8px;
  justify-content: flex-end;
}
.topbar__link {
  color: var(--text-subdued);
  font-weight: 700;
  font-size: 14px;
}
.topbar__link:hover {
  color: #fff;
}
.topbar__user {
  position: relative;
  cursor: pointer;
}
.topbar__avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #535353;
  color: #fff;
  display: grid;
  place-items: center;
  font-weight: 700;
}
.topbar__menu {
  position: absolute;
  right: 0;
  top: 42px;
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
