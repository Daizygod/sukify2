import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    // File watching over a Windows bind mount needs polling.
    watch: { usePolling: true },
    // HMR websocket must reach the browser on the host-mapped port.
    hmr: { host: 'localhost', port: 5173 },
  },
})
