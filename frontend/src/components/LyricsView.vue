<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import api from '@/lib/api'
import Icon from './Icon.vue'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'

const player = usePlayerStore()
const ui = useUiStore()

const lyrics = ref(null) // { found, synced, plain }
const lines = ref([]) // [{ ms, text }]
const listEl = ref(null)

const bg = computed(() => player.currentTrack?.release?.colors?.background || '#7f1d33')

function parseLrc(lrc) {
  const out = []
  for (const raw of lrc.split('\n')) {
    const stamps = [...raw.matchAll(/\[(\d+):(\d+(?:\.\d+)?)\]/g)]
    if (!stamps.length) continue
    const text = raw.replace(/\[.*?\]/g, '').trim()
    for (const s of stamps) {
      out.push({ ms: (Number(s[1]) * 60 + Number(s[2])) * 1000, text })
    }
  }
  return out.sort((a, b) => a.ms - b.ms)
}

async function load() {
  lyrics.value = null
  lines.value = []
  const t = player.currentTrack
  if (!t) return
  try {
    const { data } = await api.get(`/tracks/${t.id}/lyrics`)
    lyrics.value = data
    if (data.synced) lines.value = parseLrc(data.synced)
  } catch {
    lyrics.value = { found: false }
  }
}
watch(() => player.currentTrack?.id, load, { immediate: true })

const activeIndex = computed(() => {
  const pos = player.positionMs
  let idx = -1
  for (let i = 0; i < lines.value.length; i++) {
    if (lines.value[i].ms <= pos) idx = i
    else break
  }
  return idx
})

watch(activeIndex, async (i) => {
  if (i < 0) return
  await nextTick()
  const el = listEl.value?.querySelector(`[data-i="${i}"]`)
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
})

function seekTo(line) {
  player.seek(line.ms)
}
</script>

<template>
  <div class="ly" :style="{ '--ly-bg': bg }">
    <div class="ly__top">
      <span class="ly__context">{{ player.currentTrack?.title || 'Текст' }}</span>
      <button class="ly__btn" title="Закрыть" @click="ui.lyricsOpen = false">
        <Icon name="plus" :size="18" style="transform: rotate(45deg)" />
      </button>
    </div>

    <div ref="listEl" class="ly__scroll">
      <template v-if="lines.length">
        <p
          v-for="(l, i) in lines"
          :key="i"
          class="ly__line"
          :class="{ 'ly__line--past': i < activeIndex, 'ly__line--active': i === activeIndex }"
          :data-i="i"
          @click="seekTo(l)"
        >
          {{ l.text || '♪' }}
        </p>
      </template>
      <template v-else-if="lyrics?.plain">
        <p v-for="(l, i) in lyrics.plain.split('\n')" :key="i" class="ly__line ly__line--static">
          {{ l || ' ' }}
        </p>
      </template>
      <p v-else-if="lyrics && !lyrics.found" class="ly__none">
        Для этого трека текст пока не найден.
      </p>
      <p v-else class="ly__none">Ищу текст…</p>
    </div>
  </div>
</template>

<style scoped>
.ly {
  position: fixed;
  top: var(--topbar-height);
  bottom: var(--player-height);
  left: 8px;
  right: 8px;
  z-index: 58;
  border-radius: var(--radius);
  background: var(--ly-bg);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.ly__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
}
.ly__context {
  font-weight: 700;
  font-size: 16px;
}
.ly__btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: rgba(255, 255, 255, 0.8);
}
.ly__btn:hover {
  color: #fff;
  background: rgba(0, 0, 0, 0.2);
}
.ly__scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 64px 40vh;
}
.ly__line {
  font-size: clamp(24px, 3vw, 36px);
  font-weight: 800;
  letter-spacing: -0.01em;
  line-height: 1.3;
  margin-bottom: 18px;
  color: rgba(0, 0, 0, 0.6);
  cursor: pointer;
  transition: color 0.2s ease;
  max-width: 900px;
}
.ly__line:hover {
  color: #fff;
}
.ly__line--past {
  color: rgba(255, 255, 255, 0.55);
}
.ly__line--active {
  color: #fff;
}
.ly__line--static {
  color: rgba(255, 255, 255, 0.85);
  cursor: default;
}
.ly__none {
  color: rgba(255, 255, 255, 0.8);
  font-size: 20px;
  font-weight: 700;
}
</style>
