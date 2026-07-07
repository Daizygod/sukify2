import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/', name: 'home', component: () => import('@/pages/HomePage.vue') },
  { path: '/search', name: 'search', component: () => import('@/pages/SearchPage.vue') },
  { path: '/search/:q', name: 'search-query', component: () => import('@/pages/SearchPage.vue') },
  { path: '/artist/:slug', name: 'artist', component: () => import('@/pages/ArtistPage.vue') },
  { path: '/release/:slug', name: 'release', component: () => import('@/pages/ReleasePage.vue') },
  { path: '/playlist/:id', name: 'playlist', component: () => import('@/pages/PlaylistPage.vue') },
  { path: '/liked', name: 'liked', component: () => import('@/pages/LikedSongsPage.vue'), meta: { auth: true } },
  { path: '/user/:username', name: 'profile', component: () => import('@/pages/ProfilePage.vue') },
  { path: '/login', name: 'login', component: () => import('@/pages/LoginPage.vue'), meta: { guest: true } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.auth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guest && auth.isAuthenticated) {
    return { name: 'home' }
  }
})

export default router
