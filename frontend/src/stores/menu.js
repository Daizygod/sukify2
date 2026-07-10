import { defineStore } from 'pinia'

/**
 * Global track context menu (the «...» / right-click menu).
 * opts:
 *   queueQid   — the manual-queue id, enables «Удалить из очереди»
 *   upcomingIndex — index in the upcoming context list, enables removal
 */
export const useMenuStore = defineStore('menu', {
  state: () => ({
    open: false,
    x: 0,
    y: 0,
    track: null,
    opts: {},
  }),
  actions: {
    openMenu(event, track, opts = {}) {
      this.track = track
      this.opts = opts
      this.x = event.clientX
      this.y = event.clientY
      this.open = true
    },
    close() {
      this.open = false
    },
  },
})
