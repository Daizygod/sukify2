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
    // Меню сущности (альбом/плейлист/микс/исполнитель/любимые):
    // { type, id?, slug?, n?, title, pinType?, pinId? }
    entity: null,
    opts: {},
  }),
  actions: {
    openMenu(event, track, opts = {}) {
      this.track = track
      this.entity = null
      this.opts = opts
      this.x = event.clientX
      this.y = event.clientY
      this.open = true
    },
    openEntityMenu(event, entity) {
      this.entity = entity
      this.track = null
      this.opts = {}
      this.x = event.clientX
      this.y = event.clientY
      this.open = true
    },
    close() {
      this.open = false
    },
  },
})
