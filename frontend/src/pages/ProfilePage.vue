<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/lib/api'
import MediaCard from '@/components/MediaCard.vue'

const route = useRoute()
const profile = ref(null)

async function load(username) {
  const { data } = await api.get(`/users/${username}`)
  profile.value = data
}
watch(() => route.params.username, (u) => u && load(u), { immediate: true })
</script>

<template>
  <div v-if="profile" class="profile">
    <div class="profile__hero">
      <div class="profile__avatar">{{ (profile.user.name || '?')[0].toUpperCase() }}</div>
      <div>
        <span class="profile__kind">Profile</span>
        <h1 class="profile__name">{{ profile.user.name }}</h1>
        <div class="muted">{{ profile.public_playlists_count }} public playlists</div>
      </div>
    </div>

    <div class="profile__body">
      <h2 class="section-title">Public Playlists</h2>
      <div class="grid-cards">
        <MediaCard
          v-for="p in profile.playlists"
          :key="p.id"
          :to="{ name: 'playlist', params: { id: p.id } }"
          :cover="p.cover_url ? { 300: p.cover_url } : null"
          :title="p.title"
          :subtitle="`By ${profile.user.name}`"
        />
      </div>
      <p v-if="!profile.playlists.length" class="muted">No public playlists yet.</p>
    </div>
  </div>
</template>

<style scoped>
.profile__hero {
  display: flex;
  align-items: flex-end;
  gap: 24px;
  padding: 84px 24px 24px;
  background: linear-gradient(180deg, #535353 0, rgba(0, 0, 0, 0.4));
}
.profile__avatar {
  width: 200px;
  height: 200px;
  border-radius: 50%;
  background: #333;
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 72px;
  font-weight: 700;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5);
}
.profile__kind {
  font-size: 13px;
  font-weight: 600;
}
.profile__name {
  font-size: clamp(40px, 7vw, 80px);
  font-weight: 800;
  letter-spacing: -0.04em;
  margin: 12px 0;
}
.profile__body {
  padding: 24px;
  background: #121212;
}
</style>
