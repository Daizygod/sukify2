import { defineStore } from 'pinia'
import api, { ensureCsrf } from '@/lib/api'

let fetchPromise = null

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null,
    ready: false,
  }),
  getters: {
    isAuthenticated: (s) => !!s.user,
    isAdmin: (s) => !!s.user?.is_admin,
  },
  actions: {
    fetchUser() {
      // Memoized: the router guard and main.js may both await the same request.
      if (!fetchPromise) {
        fetchPromise = (async () => {
          try {
            const { data } = await api.get('/me')
            this.user = data.data
          } catch {
            this.user = null
          } finally {
            this.ready = true
          }
        })()
      }
      return fetchPromise
    },

    async login(credentials) {
      await ensureCsrf()
      const { data } = await api.post('/login', credentials)
      this.user = data.data
      await this.bootSession()
      return this.user
    },

    async register(payload) {
      await ensureCsrf()
      const { data } = await api.post('/register', payload)
      this.user = data.data
      await this.bootSession()
      return this.user
    },

    /** Start the per-session stores (also runs on SPA login without reload). */
    async bootSession() {
      const [{ usePlayerStore }, { useLibraryStore }, { useDeviceStore }] = await Promise.all([
        import('@/stores/player'),
        import('@/stores/library'),
        import('@/stores/devices'),
      ])
      usePlayerStore().loadSettings()
      useLibraryStore().load().catch(() => {})
      useDeviceStore().init()
    },

    async logout() {
      await api.post('/logout')
      this.user = null
      // Чужая сессия не должна всплыть у следующего пользователя.
      try {
        localStorage.removeItem('sukify.playerSession')
      } catch {}
    },
  },
})
