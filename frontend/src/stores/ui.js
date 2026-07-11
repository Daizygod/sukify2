import { defineStore } from 'pinia'

const LEFT_MIN = 280
const LEFT_MAX = 420
// Правая панель Spotify — 420px максимум.
const RIGHT_MIN = 280
const RIGHT_MAX = 420

function load(key, fallback, max = Infinity) {
  const v = Number(localStorage.getItem(key))
  return Number.isFinite(v) && v > 0 ? Math.min(v, max) : fallback
}

export const useUiStore = defineStore('ui', {
  state: () => ({
    leftWidth: load('ui.leftWidth', 340, LEFT_MAX),
    rightWidth: load('ui.rightWidth', 420, RIGHT_MAX),
    rightOpen: localStorage.getItem('ui.rightOpen') !== '0',
    // What the right panel shows: 'nowplaying' | 'queue' | 'connect' | 'friends'
    rightView: localStorage.getItem('ui.rightView') || 'nowplaying',
    fullscreenOpen: false,
    lyricsOpen: false,
    // Трек-источник для «Создать переход…» из контекстного меню.
    transitionFrom: null,
    listCompact: localStorage.getItem('ui.listCompact') === '1',
  }),
  actions: {
    setLeftWidth(px) {
      this.leftWidth = Math.min(Math.max(px, LEFT_MIN), LEFT_MAX)
      localStorage.setItem('ui.leftWidth', this.leftWidth)
    },
    setRightWidth(px) {
      this.rightWidth = Math.min(Math.max(px, RIGHT_MIN), RIGHT_MAX)
      localStorage.setItem('ui.rightWidth', this.rightWidth)
    },
    /** Player icons toggle their own view: same view closes, other view switches. */
    openRight(view) {
      if (this.rightOpen && this.rightView === view) {
        this.rightOpen = false
      } else {
        this.rightOpen = true
        this.rightView = view
      }
      this.persist()
    },
    closeRight() {
      this.rightOpen = false
      this.persist()
    },
    toggleRight() {
      this.openRight('nowplaying')
    },
    toggleListCompact() {
      this.listCompact = !this.listCompact
      localStorage.setItem('ui.listCompact', this.listCompact ? '1' : '0')
    },
    persist() {
      localStorage.setItem('ui.rightOpen', this.rightOpen ? '1' : '0')
      localStorage.setItem('ui.rightView', this.rightView)
    },
  },
})
