<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import Icon from './Icon.vue'
import api from '@/lib/api'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toasts'
import { useLyrics } from '@/composables/useLyrics'

const player = usePlayerStore()
const ui = useUiStore()
const auth = useAuthStore()
const toasts = useToastStore()

const { lyrics, lines, activeIndex } = useLyrics()
const listEl = ref(null)

const bg = computed(() => player.currentTrack?.release?.colors?.background || '#7f1d33')

watch(activeIndex, async (i) => {
  if (i < 0) return
  await nextTick()
  const el = listEl.value?.querySelector(`[data-i="${i}"]`)
  el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
})

function seekTo(line) {
  player.seek(line.ms)
}

// «Текст неверный» — жалоба на запись LRCLIB, откуда он пришёл.
const reporting = ref(false)
async function reportLyrics() {
  const track = player.currentTrack
  if (!track || reporting.value) return
  reporting.value = true
  try {
    const reason = window.prompt('Что не так с текстом? (необязательно)') ?? ''
    const { data } = await api.post(`/tracks/${track.id}/lyrics/flag`, { reason })
    toasts.show(data.message || 'Жалоба отправлена')
  } catch (e) {
    toasts.show(e?.response?.data?.message || 'Не удалось отправить жалобу')
  } finally {
    reporting.value = false
  }
}
</script>

<template>
  <div class="ly" :style="{ '--ly-bg': bg }">
    <div class="ly__top">
      <span class="ly__context">{{ player.currentTrack?.title || 'Текст' }}</span>
      <div class="ly__actions">
        <!-- Жалоба уходит на LRCLIB, откуда мы берём текст (только админ). -->
        <button
          v-if="auth.isAdmin && lyrics?.found"
          class="ly__report"
          :disabled="reporting"
          title="Сообщить на LRCLIB, что текст неверный"
          @click="reportLyrics"
        >
          {{ reporting ? 'Отправляем…' : 'Текст неверный' }}
        </button>
        <button class="ly__btn" title="Закрыть" @click="ui.lyricsOpen = false">
          <Icon name="close" :size="16" />
        </button>
      </div>
    </div>

    <div ref="listEl" class="ly__scroll">
      <!-- Как в Spotify: честно предупреждаем, что тайм-кодов нет. -->
      <p v-if="!lines.length && lyrics?.plain" class="ly__notice">
        Этот текст ещё не синхронизирован с треком.
      </p>
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
/* Занимает центральную колонку целиком — сайдбары и плеер остаются видны.
   Размер строк считаем от ширины колонки (cqw), а не экрана: панели можно
   растянуть, и текст должен ужиматься вместе с колонкой. */
.ly {
  position: absolute;
  inset: 0;
  z-index: 5;
  container-type: inline-size;
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
.ly__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ly__report {
  border: 1px solid rgba(255, 255, 255, 0.5);
  color: #fff;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  padding: 7px 14px;
}
.ly__report:hover:not(:disabled) {
  border-color: #fff;
  background: rgba(0, 0, 0, 0.2);
}
.ly__report:disabled {
  opacity: 0.6;
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
  padding: 24px clamp(24px, 6cqw, 64px) 40vh;
}
.ly__notice {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.75);
  margin-bottom: 28px;
}
/* Размер строк снят с оригинала: текст крупный, почти как заголовок. */
.ly__line {
  font-size: clamp(26px, 7cqw, 52px);
  font-weight: 800;
  letter-spacing: -0.01em;
  line-height: 1.22;
  margin-bottom: 30px;
  color: rgba(0, 0, 0, 0.6);
  cursor: pointer;
  transition: color 0.2s ease;
  max-width: 1100px;
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
/* Мобильный: на весь экран, поверх мини-плеера и навигации. */
@media (max-width: 768px) {
  .ly {
    position: fixed;
    inset: 0;
    border-radius: 0;
    z-index: 80;
  }
  .ly__scroll {
    padding: 8px 20px 40vh;
  }
  .ly__line {
    font-size: 26px;
    margin-bottom: 16px;
  }
}
</style>
