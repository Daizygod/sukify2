<script setup>
import { ref, watch, onMounted } from 'vue'
import { RouterLink, useRouter, useRoute } from 'vue-router'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import api from '@/lib/api'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toasts'
import { useUiStore } from '@/stores/ui'

const auth = useAuthStore()
const toasts = useToastStore()
const ui = useUiStore()
const router = useRouter()
const route = useRoute()
const menuOpen = ref(false)
const bellOpen = ref(false)
const notifications = ref([])
const hasUnseen = ref(false)
const q = ref(route.params.q || '')

async function loadNotifications() {
  if (!auth.isAuthenticated) return
  try {
    const { data } = await api.get('/me/notifications')
    notifications.value = data.data
    const lastSeen = localStorage.getItem('bell.seen') || ''
    hasUnseen.value = notifications.value.some((r) => (r.release_date || '') > lastSeen)
  } catch {
    notifications.value = []
  }
}
onMounted(loadNotifications)

function toggleBell() {
  bellOpen.value = !bellOpen.value
  if (bellOpen.value && notifications.value.length) {
    localStorage.setItem('bell.seen', notifications.value[0].release_date || '')
    hasUnseen.value = false
  }
}

async function installApp() {
  const p = window.__installPrompt
  if (p) {
    p.prompt()
    const { outcome } = await p.userChoice
    if (outcome === 'accepted') toasts.show('Sukify устанавливается…')
    window.__installPrompt = null
  } else {
    toasts.show('Открой меню браузера → «Установить приложение»')
  }
}

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
        <Icon :name="route.name === 'home' ? 'homeFill' : 'home'" :size="24" />
      </RouterLink>
      <div class="topbar__search">
        <Icon name="search" :size="24" class="topbar__searchicon" />
        <input v-model="q" class="topbar__input" placeholder="Что хочешь включить?" />
        <div class="topbar__divider"></div>
        <RouterLink to="/search" class="topbar__browse" title="Обзор">
          <Icon name="browse" :size="24" />
        </RouterLink>
      </div>
    </div>

    <div class="topbar__right">
      <template v-if="auth.isAuthenticated">
        <button class="topbar__install" @click="installApp">
          <Icon name="install" :size="16" />
          <span>Установить приложение</span>
        </button>
        <div class="topbar__bellwrap">
          <button class="topbar__ghost topbar__bellbtn" :class="{ 'topbar__bellbtn--dot': hasUnseen }" title="Что нового" @click="toggleBell">
            <Icon name="bell" :size="16" />
          </button>
          <div v-if="bellOpen" class="topbar__bell" @mouseleave="bellOpen = false">
            <div class="topbar__bellhead">Что нового</div>
            <template v-if="notifications.length">
              <RouterLink
                v-for="r in notifications"
                :key="r.id"
                :to="{ name: 'release', params: { slug: r.slug } }"
                class="topbar__notif"
                @click="bellOpen = false"
              >
                <CoverImage :cover="r.cover" :size="48" class="topbar__notifcover" />
                <div class="topbar__notifmeta">
                  <div class="topbar__notiftitle">{{ r.title }}</div>
                  <div class="topbar__notifsub">Новый релиз • {{ r.artist?.name }}</div>
                </div>
              </RouterLink>
            </template>
            <p v-else class="topbar__bellempty">Пока нет новых уведомлений. Здесь появятся новые релизы исполнителей, на которых ты подписан(-а).</p>
          </div>
        </div>
        <button class="topbar__ghost" :class="{ 'topbar__ghost--on': ui.rightOpen && ui.rightView === 'friends' }" title="Активность друзей" @click="ui.openRight('friends')">
          <Icon name="friends" :size="16" />
        </button>
        <RouterLink v-if="auth.isAdmin" to="/admin" class="topbar__link">Админ</RouterLink>
        <div class="topbar__user" @click="menuOpen = !menuOpen">
          <div class="topbar__avatar">{{ (auth.user.name || '?')[0].toUpperCase() }}</div>
          <div v-if="menuOpen" class="topbar__menu" @click.stop>
            <RouterLink :to="{ name: 'profile', params: { username: auth.user.username || auth.user.id } }" class="topbar__mi" @click="menuOpen=false">Профиль</RouterLink>
            <RouterLink :to="{ name: 'stats' }" class="topbar__mi" @click="menuOpen=false">Твоя статистика</RouterLink>
            <RouterLink :to="{ name: 'history' }" class="topbar__mi" @click="menuOpen=false">История прослушивания</RouterLink>
            <RouterLink :to="{ name: 'import' }" class="topbar__mi" @click="menuOpen=false">Импорт из Spotify</RouterLink>
            <RouterLink :to="{ name: 'settings' }" class="topbar__mi" @click="menuOpen=false">Настройки</RouterLink>
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
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
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
  /* Как в Spotify: блок «домой + поиск» отцентрован по вьюпорту. */
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 8px;
  width: clamp(350px, 42vw, 546px);
}
.topbar__home {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: #1f1f1f;
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
  background: #1f1f1f; /* цвет пилюли поиска Spotify */
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
  font-family: inherit;
  font-size: 16px;
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
  font-size: 14px;
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
.topbar__ghost--on {
  color: var(--accent);
}
.topbar__bellwrap {
  position: relative;
}
.topbar__bell {
  position: absolute;
  right: 0;
  top: 42px;
  width: 300px;
  background: #282828;
  border-radius: 8px;
  padding: 16px;
  box-shadow: 0 16px 24px rgba(0, 0, 0, 0.4);
  z-index: 40;
}
.topbar__bellhead {
  font-weight: 700;
  font-size: 16px;
  margin-bottom: 8px;
}
.topbar__bellempty {
  color: var(--text-subdued);
  font-size: 14px;
  line-height: 1.5;
}
.topbar__bellbtn {
  position: relative;
}
.topbar__bellbtn--dot::after {
  content: '';
  position: absolute;
  top: 4px;
  right: 4px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #3d91f4;
}
.topbar__notif {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px;
  border-radius: 6px;
}
.topbar__notif:hover {
  background: rgba(255, 255, 255, 0.08);
}
.topbar__notifcover {
  width: 48px;
  flex: 0 0 48px;
  border-radius: 4px;
}
.topbar__notifmeta {
  min-width: 0;
}
.topbar__notiftitle {
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.topbar__notifsub {
  color: var(--text-subdued);
  font-size: 12px;
  margin-top: 2px;
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
