/**
 * Оверлейный скроллбар в стиле Spotify: нативный скрыт полностью (не
 * резервирует полосу — контент тянется до края), поверх рисуется плоский
 * ползунок, который виден только при наведении на панель или во время
 * прокрутки. Директива: v-osbar на скроллящемся элементе; родитель
 * должен быть позиционирован (иначе станет relative).
 */
export const vOsbar = {
  mounted(el) {
    el.classList.add('osbar-host')
    const parent = el.parentElement
    if (!parent) return
    if (getComputedStyle(parent).position === 'static') parent.style.position = 'relative'

    const bar = document.createElement('div')
    bar.className = 'osbar'
    const thumb = document.createElement('div')
    thumb.className = 'osbar__thumb'
    bar.appendChild(thumb)
    parent.appendChild(bar)

    let hovered = false
    let hideTimer = null

    const update = () => {
      const sh = el.scrollHeight
      const ch = el.clientHeight
      if (sh <= ch + 1) {
        bar.style.display = 'none'
        return
      }
      bar.style.display = 'block'
      const trackH = bar.clientHeight
      const th = Math.max((ch / sh) * trackH, 40)
      const top = (el.scrollTop / (sh - ch)) * (trackH - th)
      thumb.style.height = `${th}px`
      thumb.style.transform = `translateY(${top}px)`
    }
    const show = () => {
      clearTimeout(hideTimer)
      update()
      bar.classList.add('osbar--on')
    }
    const hide = () => {
      clearTimeout(hideTimer)
      hideTimer = setTimeout(() => bar.classList.remove('osbar--on'), 500)
    }

    const onEnter = () => { hovered = true; show() }
    const onLeave = () => { hovered = false; hide() }
    const onScroll = () => { show(); if (!hovered) hide() }
    parent.addEventListener('mouseenter', onEnter)
    parent.addEventListener('mouseleave', onLeave)
    el.addEventListener('scroll', onScroll, { passive: true })

    // Перетаскивание ползунка.
    let drag = null
    thumb.addEventListener('pointerdown', (e) => {
      drag = { y: e.clientY, top: el.scrollTop }
      thumb.setPointerCapture(e.pointerId)
      bar.classList.add('osbar--drag')
      e.preventDefault()
    })
    thumb.addEventListener('pointermove', (e) => {
      if (!drag) return
      el.scrollTop = drag.top + (e.clientY - drag.y) * (el.scrollHeight / bar.clientHeight)
    })
    thumb.addEventListener('pointerup', () => {
      drag = null
      bar.classList.remove('osbar--drag')
      if (!hovered) hide()
    })

    // Следим за изменением размеров и сменой контента (роутинг, загрузка).
    const ro = new ResizeObserver(update)
    ro.observe(el)
    if (el.firstElementChild) ro.observe(el.firstElementChild)
    const mo = new MutationObserver(() => {
      update()
      if (el.firstElementChild) ro.observe(el.firstElementChild)
    })
    mo.observe(el, { childList: true })

    update()
    el.__osbar = {
      destroy() {
        ro.disconnect()
        mo.disconnect()
        parent.removeEventListener('mouseenter', onEnter)
        parent.removeEventListener('mouseleave', onLeave)
        el.removeEventListener('scroll', onScroll)
        bar.remove()
      },
    }
  },
  unmounted(el) {
    el.__osbar?.destroy()
    delete el.__osbar
  },
}
