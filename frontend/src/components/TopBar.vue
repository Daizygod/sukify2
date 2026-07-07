<script setup>
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const menuOpen = ref(false)

async function logout() {
  await auth.logout()
  menuOpen.value = false
  router.push('/')
}
</script>

<template>
  <header class="topbar">
    <div class="topbar__nav">
      <button class="topbar__round" @click="router.back()" aria-label="Back">‹</button>
      <button class="topbar__round" @click="router.forward()" aria-label="Forward">›</button>
    </div>

    <div class="topbar__right">
      <template v-if="auth.isAuthenticated">
        <RouterLink v-if="auth.isAdmin" :to="{ path: '/admin' }" class="topbar__admin">Admin</RouterLink>
        <div class="topbar__user" @click="menuOpen = !menuOpen">
          <div class="topbar__avatar">{{ (auth.user.name || '?')[0].toUpperCase() }}</div>
          <div v-if="menuOpen" class="topbar__menu" @click.stop>
            <RouterLink :to="{ name: 'profile', params: { username: auth.user.username || auth.user.id } }" class="topbar__mi" @click="menuOpen=false">Profile</RouterLink>
            <button class="topbar__mi" @click="logout">Log out</button>
          </div>
        </div>
      </template>
      <template v-else>
        <RouterLink to="/login" class="topbar__signup">Sign up</RouterLink>
        <RouterLink to="/login" class="btn-primary topbar__login">Log in</RouterLink>
      </template>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  height: var(--topbar-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  position: sticky;
  top: 0;
  z-index: 10;
}
.topbar__nav {
  display: flex;
  gap: 8px;
}
.topbar__round {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 20px;
  display: grid;
  place-items: center;
}
.topbar__right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.topbar__admin {
  color: var(--text-subdued);
  font-weight: 700;
  font-size: 14px;
}
.topbar__admin:hover {
  color: #fff;
}
.topbar__signup {
  color: var(--text-subdued);
  font-weight: 700;
}
.topbar__signup:hover {
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
  top: 40px;
  background: #282828;
  border-radius: 4px;
  min-width: 180px;
  padding: 4px;
  box-shadow: 0 16px 24px rgba(0, 0, 0, 0.4);
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
