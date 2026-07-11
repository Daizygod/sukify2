<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from './Icon.vue'
import api from '@/lib/api'
import { getRealtime } from '@/lib/realtime'
import { useUiStore } from '@/stores/ui'
import { formatRelativeDate } from '@/lib/format'

const ui = useUiStore()
const items = ref([])
const loading = ref(true)
let subs = []

async function load() {
  try {
    const { data } = await api.get('/me/friends-activity')
    items.value = data.data
    await subscribeAll()
  } finally {
    loading.value = false
  }
}

/** Живая активность: канал activity:{friendId} каждого друга. */
async function subscribeAll() {
  const client = await getRealtime()
  for (const i of items.value) {
    const channel = `activity:${i.user.id}`
    let sub = client.getSubscription(channel)
    if (!sub) {
      sub = client.newSubscription(channel)
      sub.on('publication', (ctx) => {
        const msg = ctx.data
        const item = items.value.find((x) => x.user.id === msg.user_id)
        if (item) {
          item.activity = {
            track_id: msg.track_id,
            title: msg.title,
            artists: msg.artists,
            release_slug: msg.release_slug,
            at: msg.at,
          }
        }
      })
      sub.subscribe()
      subs.push(sub)
    }
  }
}

onMounted(load)
onUnmounted(() => {
  subs.forEach((s) => s.unsubscribe())
  subs = []
})
</script>

<template>
  <aside class="fp">
    <div class="fp__head">
      <span class="fp__title">Активность друзей</span>
      <button class="fp__close" title="Закрыть" @click="ui.closeRight()">
        <Icon name="plus" :size="16" style="transform: rotate(45deg)" />
      </button>
    </div>
    <div class="fp__body">
      <template v-if="items.length">
        <div v-for="i in items" :key="i.user.id" class="fp__friend">
          <div class="fp__avatar">{{ (i.user.name || '?')[0].toUpperCase() }}</div>
          <div class="fp__meta">
            <RouterLink :to="{ name: 'profile', params: { username: i.user.username || i.user.id } }" class="fp__name">
              {{ i.user.name }}
            </RouterLink>
            <template v-if="i.activity">
              <RouterLink
                v-if="i.activity.release_slug"
                :to="{ name: 'release', params: { slug: i.activity.release_slug } }"
                class="fp__track"
              >
                {{ i.activity.title }} • {{ i.activity.artists }}
              </RouterLink>
              <span v-else class="fp__track">{{ i.activity.title }} • {{ i.activity.artists }}</span>
              <span class="fp__time muted">{{ formatRelativeDate(i.activity.at) }}</span>
            </template>
            <span v-else class="fp__track muted">Давно ничего не слушал(-а)</span>
          </div>
          <Icon v-if="i.activity" name="volume" :size="12" class="fp__live" />
        </div>
      </template>
      <template v-else-if="!loading">
        <p class="fp__lead">Смотри, что слушают друзья.</p>
        <p class="muted fp__text">
          Подпишись на друзей через их профиль — и здесь в реальном времени будет
          видно, какие треки они включают.
        </p>
      </template>
    </div>
  </aside>
</template>

<style scoped>
.fp {
  height: 100%;
  background: var(--bg-elevated);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.fp__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 16px 8px;
}
.fp__title {
  font-weight: 700;
  font-size: 16px;
}
.fp__close {
  color: var(--text-subdued);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
}
.fp__close:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.fp__body {
  padding: 8px;
  overflow-y: auto;
  flex: 1;
  min-height: 0;
}
.fp__friend {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 8px;
  border-radius: 6px;
}
.fp__friend:hover {
  background: rgba(255, 255, 255, 0.06);
}
.fp__avatar {
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  border-radius: 50%;
  background: #535353;
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 14px;
}
.fp__meta {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.fp__name {
  font-weight: 700;
  font-size: 14px;
}
.fp__name:hover {
  text-decoration: underline;
}
.fp__track {
  font-size: 12px;
  color: var(--text-subdued);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fp__track:hover {
  color: #fff;
}
.fp__time {
  font-size: 11px;
}
.fp__live {
  color: var(--accent);
  margin-top: 4px;
}
.fp__lead {
  font-weight: 700;
  margin: 8px;
}
.fp__text {
  font-size: 14px;
  line-height: 1.5;
  margin: 0 8px;
}
</style>
