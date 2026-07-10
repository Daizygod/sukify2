// Минимальный сервис-воркер: нужен, чтобы браузер считал Sukify устанавливаемым PWA.
self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()))
self.addEventListener('fetch', () => {
  // passthrough — офлайн-кэша нет, музыка стримится
})
