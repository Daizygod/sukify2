/**
 * Кастомные подсказки в стиле Spotify (tippy): нативный title подавляется,
 * вместо него всплывает тёмная плашка над элементом. Работает глобально
 * для любых [title] — включая динамически отрендеренные.
 */
let tipEl = null
let timer = null
let current = null

function ensureEl() {
  if (tipEl) return tipEl
  tipEl = document.createElement('div')
  tipEl.className = 'tooltip'
  tipEl.setAttribute('role', 'tooltip')
  document.body.appendChild(tipEl)
  return tipEl
}

function show(target) {
  const text = target.dataset.tooltip
  if (!text) return
  const el = ensureEl()
  el.textContent = text
  el.style.display = 'block'
  // Сначала меряем, потом позиционируем: над центром цели, с зажимом в экран.
  const r = target.getBoundingClientRect()
  el.style.left = '0px'
  el.style.top = '0px'
  const w = el.offsetWidth
  const h = el.offsetHeight
  let x = r.left + r.width / 2 - w / 2
  x = Math.max(8, Math.min(x, window.innerWidth - w - 8))
  let y = r.top - h - 8
  if (y < 8) y = r.bottom + 8
  el.style.left = `${Math.round(x)}px`
  el.style.top = `${Math.round(y)}px`
  el.classList.add('tooltip--on')
  target.setAttribute('aria-describedby', 'app-tooltip')
}

function hide() {
  clearTimeout(timer)
  if (tipEl) {
    tipEl.classList.remove('tooltip--on')
    tipEl.style.display = 'none'
  }
  if (current) current.removeAttribute('aria-describedby')
  current = null
}

export function initTooltips() {
  document.addEventListener('mouseover', (e) => {
    const t = e.target.closest?.('[title], [data-tooltip]')
    if (!t) return
    // Забираем title в data-атрибут, чтобы браузерная подсказка не появлялась.
    if (t.hasAttribute('title')) {
      t.dataset.tooltip = t.getAttribute('title')
      t.removeAttribute('title')
    }
    if (t === current) return
    hide()
    current = t
    timer = setTimeout(() => show(t), 350)
    const off = () => {
      t.removeEventListener('mouseleave', off)
      t.removeEventListener('mousedown', off)
      hide()
    }
    t.addEventListener('mouseleave', off)
    t.addEventListener('mousedown', off)
  })
  // При скролле плашка не должна висеть в воздухе.
  document.addEventListener('scroll', hide, true)
}
