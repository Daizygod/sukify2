import { defineStore } from 'pinia'
import api from '@/lib/api'

export const useLibraryStore = defineStore('library', {
  state: () => ({
    playlists: [],
    likedAlbums: [],
    followedArtists: [],
    likedTrackIds: new Set(),
    pins: new Set(), // "playlist:1", "album:2", "artist:3"
    loaded: false,
  }),
  actions: {
    async load() {
      const [{ data: pl }, { data: liked }, { data: albums }, { data: artists }, { data: pins }] =
        await Promise.all([
          api.get('/library/playlists'),
          api.get('/library/liked-tracks'),
          api.get('/library/liked-albums'),
          api.get('/library/followed-artists'),
          api.get('/library/pins'),
        ])
      this.playlists = pl.data
      this.likedTrackIds = new Set(liked.data.map((t) => t.id))
      this.likedAlbums = albums.data
      this.followedArtists = artists.data
      this.pins = new Set(pins.data.map((p) => `${p.item_type}:${p.item_id}`))
      this.loaded = true
    },

    isPinned(type, id) {
      return this.pins.has(`${type}:${id}`)
    },

    async togglePin(type, id) {
      const key = `${type}:${id}`
      if (this.pins.has(key)) {
        this.pins.delete(key)
        await api.delete('/library/pins', { data: { item_type: type, item_id: id } })
        return false
      }
      this.pins.add(key)
      await api.post('/library/pins', { item_type: type, item_id: id })
      return true
    },

    async refreshPlaylists() {
      const { data } = await api.get('/library/playlists')
      this.playlists = data.data
    },

    async refreshAlbums() {
      const { data } = await api.get('/library/liked-albums')
      this.likedAlbums = data.data
    },

    async refreshArtists() {
      const { data } = await api.get('/library/followed-artists')
      this.followedArtists = data.data
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

    /** Like/unlike a release and keep the sidebar Albums tab in sync. */
    async toggleAlbumLike(release) {
      if (release.is_liked) {
        release.is_liked = false
        this.likedAlbums = this.likedAlbums.filter((r) => r.id !== release.id)
        await api.delete(`/releases/${release.id}/like`)
      } else {
        release.is_liked = true
        this.likedAlbums = [release, ...this.likedAlbums]
        await api.post(`/releases/${release.id}/like`)
      }
    },

    /** Follow/unfollow an artist and keep the sidebar Artists tab in sync. */
    async toggleFollow(artist) {
      if (artist.is_followed) {
        artist.is_followed = false
        this.followedArtists = this.followedArtists.filter((a) => a.id !== artist.id)
        await api.delete(`/artists/${artist.slug}/follow`)
      } else {
        artist.is_followed = true
        this.followedArtists = [artist, ...this.followedArtists]
        await api.post(`/artists/${artist.slug}/follow`)
      }
    },

    async createPlaylist(title = 'Мой плейлист') {
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
