import { defineStore } from 'pinia'

const LEFT_MIN = 280
const LEFT_MAX = 420
const RIGHT_MIN = 280
const RIGHT_MAX = 440

function load(key, fallback) {
  const v = Number(localStorage.getItem(key))
  return Number.isFinite(v) && v > 0 ? v : fallback
}

export const useUiStore = defineStore('ui', {
  state: () => ({
    leftWidth: load('ui.leftWidth', 340),
    rightWidth: load('ui.rightWidth', 340),
    rightOpen: localStorage.getItem('ui.rightOpen') !== '0',
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
    toggleRight() {
      this.rightOpen = !this.rightOpen
      localStorage.setItem('ui.rightOpen', this.rightOpen ? '1' : '0')
    },
  },
})
