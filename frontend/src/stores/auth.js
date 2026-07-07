import { defineStore } from 'pinia'
import api, { ensureCsrf } from '@/lib/api'

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
    async fetchUser() {
      try {
        const { data } = await api.get('/me')
        this.user = data.data
      } catch {
        this.user = null
      } finally {
        this.ready = true
      }
    },

    async login(credentials) {
      await ensureCsrf()
      const { data } = await api.post('/login', credentials)
      this.user = data.data
      return this.user
    },

    async register(payload) {
      await ensureCsrf()
      const { data } = await api.post('/register', payload)
      this.user = data.data
      return this.user
    },

    async logout() {
      await api.post('/logout')
      this.user = null
    },
  },
})
