import { ref } from 'vue'

// Единый реактивный флаг «мобильный экран» (≤768px) для всего приложения.
const mq = window.matchMedia('(max-width: 768px)')
const isMobile = ref(mq.matches)
mq.addEventListener('change', (e) => (isMobile.value = e.matches))

export function useIsMobile() {
  return isMobile
}
