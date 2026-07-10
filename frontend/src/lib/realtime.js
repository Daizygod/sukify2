import { Centrifuge } from 'centrifuge'
import api from '@/lib/api'

let client = null
let connectPromise = null

/** Lazily open (and share) the Centrifugo connection for this tab. */
export async function getRealtime() {
  if (client) return client
  if (!connectPromise) {
    connectPromise = (async () => {
      const { data } = await api.post('/realtime/connection-token')
      client = new Centrifuge(data.ws_url, {
        token: data.token,
        getToken: async () => {
          const { data: d } = await api.post('/realtime/connection-token')
          return d.token
        },
      })
      client.__playbackChannel = data.playback_channel
      client.connect()
      return client
    })()
  }
  return connectPromise
}

/** Token provider for private (session:*) channels. */
export async function subscriptionToken(channel) {
  const { data } = await api.post('/realtime/subscription-token', { channel })
  return data.token
}
