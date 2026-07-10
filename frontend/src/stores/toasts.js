import { defineStore } from 'pinia'

let nextId = 0

/** Spotify-style bottom-center toasts («Добавлено в очередь» и т.п.). */
export const useToastStore = defineStore('toasts', {
  state: () => ({
    items: [], // { id, text }
  }),
  actions: {
    show(text, ms = 2600) {
      const id = ++nextId
      this.items.push({ id, text })
      setTimeout(() => {
        this.items = this.items.filter((t) => t.id !== id)
      }, ms)
    },
  },
})
