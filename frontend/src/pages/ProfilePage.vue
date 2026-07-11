<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import api from '@/lib/api'
import MediaCard from '@/components/MediaCard.vue'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toasts'
import { plural } from '@/lib/format'

const route = useRoute()
const auth = useAuthStore()
const toasts = useToastStore()
const profile = ref(null)

async function load(username) {
  const { data } = await api.get(`/users/${username}`)
  profile.value = data
}
watch(() => route.params.username, (u) => u && load(u), { immediate: true })

async function toggleFollow() {
  const u = profile.value.user
  if (profile.value.is_followed) {
    profile.value.is_followed = false
    profile.value.followers_count--
    await api.delete(`/users/${u.username}/follow`)
  } else {
    profile.value.is_followed = true
    profile.value.followers_count++
    await api.post(`/users/${u.username}/follow`)
    toasts.show(`Ты подписан(-а) на ${u.name} — активность появится в панели друзей`)
  }
}
</script>

<template>
  <div v-if="profile" class="profile">
    <div class="profile__hero">
      <div class="profile__avatar">{{ (profile.user.name || '?')[0].toUpperCase() }}</div>
      <div>
        <span class="profile__kind">Профиль</span>
        <h1 class="profile__name">{{ profile.user.name }}</h1>
        <div class="muted profile__meta">
          <span>Открытых плейлистов: {{ profile.public_playlists_count }}</span>
          <span>• {{ profile.followers_count }} {{ plural(profile.followers_count, 'подписчик', 'подписчика', 'подписчиков') }}</span>
          <span>• {{ profile.following_count }} подписок</span>
        </div>
      </div>
    </div>

    <div class="profile__body">
      <div v-if="auth.isAuthenticated && !profile.is_me" class="profile__actions">
        <button class="profile__follow" @click="toggleFollow">
          {{ profile.is_followed ? 'Вы подписаны' : 'Подписаться' }}
        </button>
      </div>

      <h2 class="section-title">Открытые плейлисты</h2>
      <div class="grid-cards">
        <MediaCard
          v-for="p in profile.playlists"
          :key="p.id"
          :to="{ name: 'playlist', params: { id: p.id } }"
          :cover="p.cover_url ? { 300: p.cover_url } : null"
          :title="p.title"
          :subtitle="`Автор: ${profile.user.name}`"
        />
      </div>
      <p v-if="!profile.playlists.length" class="muted">Пока нет открытых плейлистов.</p>
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
  font-size: 14px;
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
.profile__meta {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.profile__actions {
  margin-bottom: 24px;
}
.profile__follow {
  border: 1px solid var(--text-muted);
  color: #fff;
  border-radius: 999px;
  padding: 7px 15px;
  font-size: 14px;
  font-weight: 700;
}
.profile__follow:hover {
  border-color: #fff;
  transform: scale(1.02);
}
</style>
