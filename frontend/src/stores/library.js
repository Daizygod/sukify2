import { defineStore } from 'pinia'
import api from '@/lib/api'

export const useLibraryStore = defineStore('library', {
  state: () => ({
    playlists: [],
    likedTrackIds: new Set(),
    loaded: false,
  }),
  actions: {
    async load() {
      const [{ data: pl }, { data: liked }] = await Promise.all([
        api.get('/library/playlists'),
        api.get('/library/liked-tracks'),
      ])
      this.playlists = pl.data
      this.likedTrackIds = new Set(liked.data.map((t) => t.id))
      this.loaded = true
    },

    async refreshPlaylists() {
      const { data } = await api.get('/library/playlists')
      this.playlists = data.data
    },

    isLiked(trackId) {
      return this.likedTrackIds.has(trackId)
    },

    async toggleLike(track) {
      if (this.likedTrackIds.has(track.id)) {
        this.likedTrackIds.delete(track.id)
        await api.delete(`/tracks/${track.id}/like`)
      } else {
        this.likedTrackIds.add(track.id)
        await api.post(`/tracks/${track.id}/like`)
      }
    },

    async createPlaylist(title = 'My Playlist') {
      const { data } = await api.post('/playlists', { title })
      await this.refreshPlaylists()
      return data.data
    },

    async addToPlaylist(playlistId, trackId) {
      await api.post(`/playlists/${playlistId}/tracks`, { track_id: trackId })
      await this.refreshPlaylists()
    },
  },
})
