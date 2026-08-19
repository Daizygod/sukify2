<script setup>
import NowPlayingPanel from './NowPlayingPanel.vue'
import QueuePanel from './QueuePanel.vue'
import ConnectPanel from './ConnectPanel.vue'
import FriendsPanel from './FriendsPanel.vue'
import AddToPlaylistPanel from './AddToPlaylistPanel.vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
</script>

<template>
  <AddToPlaylistPanel
    v-if="ui.rightView === 'add-tracks' && ui.addTracksPlaylist"
    :key="ui.addTracksPlaylist"
    :playlist-id="ui.addTracksPlaylist"
  />
  <QueuePanel v-else-if="ui.rightView === 'queue'" />
  <ConnectPanel v-else-if="ui.rightView === 'connect'" />
  <FriendsPanel v-else-if="ui.rightView === 'friends'" />
  <NowPlayingPanel v-else />
</template>
