<script setup>
import NowPlayingPanel from './NowPlayingPanel.vue'
import QueuePanel from './QueuePanel.vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
</script>

<template>
  <QueuePanel v-if="ui.rightView === 'queue'" />
  <NowPlayingPanel v-else />
</template>
