import { defineConfig } from 'vite'

// Порт зафиксирован: его же ждёт devUrl в tauri.conf.json.
export default defineConfig({
  clearScreen: false,
  server: { port: 5183, strictPort: true },
  build: { target: 'chrome105', emptyOutDir: true },
})
