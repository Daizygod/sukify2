<script setup>
import { RouterView } from 'vue-router'
import Sidebar from '@/components/Sidebar.vue'
import TopBar from '@/components/TopBar.vue'
import PlayerBar from '@/components/PlayerBar.vue'
</script>

<template>
  <div class="app">
    <Sidebar class="app__nav" />
    <main class="app__main">
      <TopBar />
      <div class="app__scroll">
        <RouterView />
      </div>
    </main>
    <PlayerBar class="app__player" />
  </div>
</template>
