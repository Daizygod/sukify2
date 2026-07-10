import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/', name: 'home', component: () => import('@/pages/HomePage.vue') },
  { path: '/search', name: 'search', component: () => import('@/pages/SearchPage.vue') },
  { path: '/search/:q', name: 'search-query', component: () => import('@/pages/SearchPage.vue') },
  { path: '/artist/:slug', name: 'artist', component: () => import('@/pages/ArtistPage.vue') },
  { path: '/artist/:slug/liked', name: 'artist-liked', component: () => import('@/pages/ArtistLikedPage.vue'), meta: { auth: true } },
  { path: '/release/:slug', name: 'release', component: () => import('@/pages/ReleasePage.vue') },
  { path: '/playlist/:id', name: 'playlist', component: () => import('@/pages/PlaylistPage.vue') },
  { path: '/liked', name: 'liked', component: () => import('@/pages/LikedSongsPage.vue'), meta: { auth: true } },
  { path: '/section/:key', name: 'section', component: () => import('@/pages/SectionPage.vue') },
  { path: '/import', name: 'import', component: () => import('@/pages/ImportPage.vue'), meta: { auth: true } },
  { path: '/genre/:name', name: 'genre', component: () => import('@/pages/GenrePage.vue') },
  { path: '/mix/:n', name: 'mix', component: () => import('@/pages/MixPage.vue'), meta: { auth: true } },
  { path: '/history', name: 'history', component: () => import('@/pages/HistoryPage.vue'), meta: { auth: true } },
  { path: '/settings', name: 'settings', component: () => import('@/pages/SettingsPage.vue'), meta: { auth: true } },
  { path: '/stats', name: 'stats', component: () => import('@/pages/StatsPage.vue'), meta: { auth: true } },
  { path: '/user/:username', name: 'profile', component: () => import('@/pages/ProfilePage.vue') },
  { path: '/login', name: 'login', component: () => import('@/pages/LoginPage.vue'), meta: { guest: true } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  // vue-router starts the initial navigation on install, before main.js has
  // finished awaiting the session — wait for it here so guards see the user.
  if (!auth.ready) await auth.fetchUser()
  if (to.meta.auth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guest && auth.isAuthenticated) {
    return { name: 'home' }
  }
})

export default router
