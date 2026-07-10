<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'

const player = usePlayerStore()
const ui = useUiStore()
const track = computed(() => player.currentTrack)
const artist = computed(() => track.value?.artists?.[0] || null)
</script>

<template>
  <aside class="np">
    <div class="np__head">
      <span class="np__title">{{ track ? track.title : 'Сейчас играет' }}</span>
      <button class="np__close" title="Закрыть" @click="ui.toggleRight()"><Icon name="plus" :size="16" style="transform:rotate(45deg)" /></button>
    </div>

    <div v-if="track" class="np__body">
      <CoverImage :cover="track.cover" :size="640" class="np__cover" />
      <div class="np__meta">
        <RouterLink v-if="track.release" :to="{ name: 'release', params: { slug: track.release.slug } }" class="np__song">{{ track.title }}</RouterLink>
        <span v-else class="np__song">{{ track.title }}</span>
        <div class="np__artists">
          <template v-for="(a, i) in track.artists" :key="a.id">
            <RouterLink :to="{ name: 'artist', params: { slug: a.slug } }">{{ a.name }}</RouterLink><span v-if="i < track.artists.length - 1">, </span>
          </template>
        </div>
      </div>

      <div v-if="artist" class="np__about">
        <div class="np__about-head">Об исполнителе</div>
        <RouterLink :to="{ name: 'artist', params: { slug: artist.slug } }" class="np__about-name">{{ artist.name }}</RouterLink>
      </div>
    </div>

    <div v-else class="np__empty">
      <p class="muted">Сейчас ничего не играет.</p>
    </div>
  </aside>
</template>

<style scoped>
.np {
  height: 100%;
  background: var(--bg-elevated);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.np__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 16px 8px;
}
.np__title {
  font-weight: 700;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.np__close {
  color: var(--text-subdued);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
}
.np__close:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.np__body {
  overflow-y: auto;
  padding: 8px 16px 16px;
}
.np__cover {
  width: 100%;
  border-radius: 8px;
  margin-bottom: 16px;
}
.np__meta {
  margin-bottom: 20px;
}
.np__song {
  display: block;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.01em;
}
.np__song:hover {
  text-decoration: underline;
}
.np__artists {
  color: var(--text-subdued);
  font-size: 14px;
  margin-top: 4px;
}
.np__artists a:hover {
  color: #fff;
  text-decoration: underline;
}
.np__about {
  background: #2a2a2a;
  border-radius: 8px;
  padding: 16px;
}
.np__about-head {
  font-weight: 700;
  margin-bottom: 8px;
}
.np__about-name {
  color: var(--text-subdued);
}
.np__about-name:hover {
  color: #fff;
  text-decoration: underline;
}
.np__empty {
  padding: 24px 16px;
}
</style>
