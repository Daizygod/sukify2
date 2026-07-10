<script setup>
import NowPlayingPanel from './NowPlayingPanel.vue'
import QueuePanel from './QueuePanel.vue'
import ConnectPanel from './ConnectPanel.vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
</script>

<template>
  <QueuePanel v-if="ui.rightView === 'queue'" />
  <ConnectPanel v-else-if="ui.rightView === 'connect'" />
  <NowPlayingPanel v-else />
</template>
