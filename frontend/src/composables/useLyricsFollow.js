import { ref, watch, nextTick, onMounted, onUnmounted } from 'vue'

/**
 * Автопрокрутка текста за песней — но с уважением к человеку, как в Spotify.
 *
 * Пока никто не трогает текст, звучащая строка плавно держится на высоте
 * `anchor` (доля высоты окна). Стоит самому покрутить — колесом, пальцем или
 * полосой прокрутки — следование выключается, и текст стоит там, куда его
 * увели: смотреть вперёд можно сколько угодно, обратно не «телепортирует».
 * Вернуться к песне — кнопкой «Синхронизация» (resync) или тапом по строке.
 *
 * Свои прокрутки мы отличаем по намерению (wheel/touch/захват полосы), а не
 * по событию scroll: его шлёт и наша собственная плавная прокрутка.
 */
export function useLyricsFollow(scroller, activeIndex, { anchor = 0.5, resetKey = null } = {}) {
  const following = ref(true)

  function lineEl(i) {
    return scroller.value?.querySelector(`[data-i="${i}"]`) || null
  }

  function scrollToLine(i, behavior = 'smooth') {
    const box = scroller.value
    const el = lineEl(i)
    if (!box || !el) return
    const top = el.getBoundingClientRect().top - box.getBoundingClientRect().top + box.scrollTop
    const target = top + el.offsetHeight / 2 - box.clientHeight * anchor
    box.scrollTo({ top: Math.max(0, target), behavior })
  }

  /** «Синхронизация»: снова едем за песней с текущей строки. */
  function resync(behavior = 'smooth') {
    following.value = true
    if (activeIndex.value >= 0) scrollToLine(activeIndex.value, behavior)
    else scroller.value?.scrollTo({ top: 0, behavior })
  }

  watch(activeIndex, async (i) => {
    if (!following.value || i < 0) return
    await nextTick()
    scrollToLine(i)
  })

  // Новый трек — новый текст: начинаем следить заново, без анимации.
  if (resetKey) {
    watch(resetKey, async () => {
      following.value = true
      await nextTick()
      if (activeIndex.value >= 0) scrollToLine(activeIndex.value, 'auto')
      else scroller.value?.scrollTo({ top: 0 })
    })
  }

  const release = () => (following.value = false)
  // Полоса прокрутки: нажатие правее содержимого — это захват ползунка.
  function onPointerDown(e) {
    const box = scroller.value
    if (box && e.target === box && e.offsetX >= box.clientWidth) release()
  }
  let bound = null
  function bind(el) {
    if (bound === el) return
    unbind()
    if (!el) return
    el.addEventListener('wheel', release, { passive: true })
    el.addEventListener('touchmove', release, { passive: true })
    el.addEventListener('pointerdown', onPointerDown)
    bound = el
  }
  function unbind() {
    if (!bound) return
    bound.removeEventListener('wheel', release)
    bound.removeEventListener('touchmove', release)
    bound.removeEventListener('pointerdown', onPointerDown)
    bound = null
  }
  watch(scroller, (el) => bind(el))
  onMounted(async () => {
    bind(scroller.value)
    await nextTick()
    // Открыли текст посреди песни — сразу встаём на звучащую строку.
    if (activeIndex.value >= 0) scrollToLine(activeIndex.value, 'auto')
  })
  onUnmounted(unbind)

  return { following, resync, scrollToLine }
}
