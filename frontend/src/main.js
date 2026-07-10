import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from '@/router'
import App from '@/App.vue'
import '@fontsource/manrope/400.css'
import '@fontsource/manrope/500.css'
import '@fontsource/manrope/600.css'
import '@fontsource/manrope/700.css'
import '@fontsource/manrope/800.css'
import '@/styles/main.css'

import { useAuthStore } from '@/stores/auth'
import { usePlayerStore } from '@/stores/player'
import { useLibraryStore } from '@/stores/library'

async function bootstrap() {
  const app = createApp(App)
  app.use(createPinia())
  app.use(router)

  // Resolve the current session before the first paint so guards behave.
  const auth = useAuthStore()
  await auth.fetchUser()

  if (auth.isAuthenticated) {
    const player = usePlayerStore()
    const library = useLibraryStore()
    player.loadSettings()
    library.load().catch(() => {})
  }

  await router.isReady()
  app.mount('#app')
}

bootstrap()
