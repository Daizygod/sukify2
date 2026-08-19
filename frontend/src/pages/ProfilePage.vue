<script setup>
import { ref, watch, computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import api from '@/lib/api'
import MediaCard from '@/components/MediaCard.vue'
import Icon from '@/components/Icon.vue'
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
    profile.value.is_friend = false
    profile.value.followers_count--
    await api.delete(`/users/${u.username}/follow`)
  } else {
    profile.value.is_followed = true
    profile.value.is_friend = profile.value.follows_me
    profile.value.followers_count++
    await api.post(`/users/${u.username}/follow`)
    toasts.show(
      profile.value.is_friend
        ? `Теперь вы друзья с ${u.name} — активность видна в панели друзей`
        : `Ты подписан(-а) на ${u.name}`
    )
  }
}

/** Одной фразой: кто вы друг другу. */
const relationLabel = computed(() => {
  const p = profile.value
  if (!p || p.is_me) return ''
  if (p.is_friend) return 'Вы друзья — подписаны друг на друга'
  if (p.follows_me) return 'Подписан(-а) на вас — подпишись в ответ, и станете друзьями'
  if (p.is_followed) return 'Вы подписаны — станете друзьями, когда подпишутся в ответ'
  return ''
})

const followLabel = computed(() => {
  const p = profile.value
  if (!p) return ''
  if (p.is_friend) return 'Вы друзья'
  if (p.is_followed) return 'Вы подписаны'
  return p.follows_me ? 'Подписаться в ответ' : 'Подписаться'
})
</script>

<template>
  <div v-if="profile" class="profile">
    <div class="profile__hero">
      <div class="profile__avatar">
        <img v-if="profile.user.avatar_url" :src="profile.user.avatar_url" alt="" />
        <span v-else>{{ (profile.user.name || '?')[0].toUpperCase() }}</span>
      </div>
      <div>
        <span class="profile__kind">Профиль</span>
        <h1 class="profile__name">{{ profile.user.name }}</h1>
        <div class="muted profile__meta">
          <span>@{{ profile.user.username }}</span>
          <span>• Открытых плейлистов: {{ profile.public_playlists_count }}</span>
          <span>• {{ profile.followers_count }} {{ plural(profile.followers_count, 'подписчик', 'подписчика', 'подписчиков') }}</span>
          <span>• {{ profile.friends_count }} {{ plural(profile.friends_count, 'друг', 'друга', 'друзей') }}</span>
        </div>
      </div>
    </div>

    <div class="profile__body">
      <div v-if="auth.isAuthenticated && !profile.is_me" class="profile__actions">
        <button class="profile__follow" :class="{ on: profile.is_friend }" @click="toggleFollow">
          {{ followLabel }}
        </button>
        <span v-if="relationLabel" class="profile__relation">
          <Icon v-if="profile.is_friend" name="friends" :size="16" />
          {{ relationLabel }}
        </span>
      </div>

      <section v-if="profile.mutual_friends?.length" class="profile__mutual">
        <h2 class="section-title">Общие друзья</h2>
        <div class="profile__people">
          <RouterLink
            v-for="f in profile.mutual_friends"
            :key="f.id"
            :to="{ name: 'profile', params: { username: f.username || f.id } }"
            class="profile__person"
          >
            <div class="profile__personava">
              <img v-if="f.avatar_url" :src="f.avatar_url" alt="" />
              <span v-else>{{ (f.name || '?')[0].toUpperCase() }}</span>
            </div>
            <span class="profile__personname">{{ f.name }}</span>
          </RouterLink>
        </div>
      </section>

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
.profile__avatar img {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
}
.profile__actions {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-bottom: 24px;
}
.profile__relation {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-subdued);
  font-size: 14px;
}
.profile__mutual {
  margin-bottom: 28px;
}
.profile__people {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
}
.profile__person {
  width: 96px;
  text-align: center;
}
.profile__personava {
  width: 96px;
  height: 96px;
  border-radius: 50%;
  background: #333;
  display: grid;
  place-items: center;
  font-size: 32px;
  font-weight: 700;
  margin-bottom: 8px;
  overflow: hidden;
}
.profile__personava img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.profile__personname {
  font-size: 13px;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.profile__person:hover .profile__personname {
  text-decoration: underline;
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
.profile__follow.on {
  border-color: var(--accent);
  color: var(--accent);
}
</style>
