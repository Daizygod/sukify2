<script setup>
import { ref, computed } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from './Icon.vue'
import LyricsSyncButton from './LyricsSyncButton.vue'
import api from '@/lib/api'
import { tint } from '@/lib/color'
import { contextRoute } from '@/lib/links'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toasts'
import { useLyrics } from '@/composables/useLyrics'
import { useLyricsFollow } from '@/composables/useLyricsFollow'
import { useIsMobile } from '@/composables/useIsMobile'

const player = usePlayerStore()
const ui = useUiStore()
const auth = useAuthStore()
const toasts = useToastStore()
const isMobile = useIsMobile()

const { lyrics, lines, activeIndex } = useLyrics()
const listEl = ref(null)

// Как в Spotify: звучащая строка держится посередине; на телефоне — чуть выше.
const { following, resync } = useLyricsFollow(listEl, activeIndex, {
  anchor: isMobile.value ? 0.38 : 0.5,
  resetKey: () => player.currentTrack?.id,
})

const bg = computed(() => player.currentTrack?.release?.colors?.background || '#7f1d33')
// Ещё не спетые строки — светлый оттенок фона (Spotify 2026), спетые — он же бледнее.
const lineTint = computed(() => tint(bg.value))
const ctxLink = computed(() => contextRoute(player.contextKey))

function seekTo(line) {
  // Тап по строке — «хочу отсюда»: перематываем и снова едем за песней.
  following.value = true
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
  <div class="ly" :style="{ '--ly-bg': bg, '--ly-tint': lineTint }">
    <div class="ly__top">
      <RouterLink v-if="ctxLink && player.contextName" :to="ctxLink" class="ly__context">{{ player.contextName }}</RouterLink>
      <span v-else class="ly__context">{{ player.currentTrack?.title || 'Текст' }}</span>
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
      <div class="ly__col">
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
        <p v-else class="ly__none ly__none--wait">Ищу текст…</p>

        <p v-if="lyrics?.found" class="ly__credit">
          {{ lyrics.is_custom ? 'Текст добавлен вручную' : 'Текст: LRCLIB' }}
        </p>
      </div>
    </div>

    <LyricsSyncButton :show="!following && lines.length > 0" @sync="resync()" />
  </div>
</template>

<style scoped>
/* Занимает центральную колонку целиком — сайдбары и плеер остаются видны.
   Размер строк считаем от ширины колонки (cqw), а не экрана: панели можно
   растянуть, и текст должен ужиматься вместе с колонкой. */
@property --ly-bg {
  syntax: '<color>';
  inherits: true;
  initial-value: #7f1d33;
}
.ly {
  position: absolute;
  inset: 0;
  z-index: 5;
  container-type: inline-size;
  border-radius: var(--radius);
  /* Цвет обложки + зерно и лёгкий объём: сверху чуть светлее, к низу глубже. */
  background-color: var(--ly-bg);
  background-image: var(--grain), linear-gradient(180deg, rgba(255, 255, 255, 0.05) 0%, rgba(0, 0, 0, 0.18) 100%);
  transition: --ly-bg 0.8s ease;
  overflow: hidden;
  animation: ly-in 0.36s cubic-bezier(0.3, 0, 0, 1);
}
@keyframes ly-in {
  from {
    opacity: 0;
    transform: translateY(14px) scale(0.985);
  }
}
/* Шапка лежит поверх текста: строки уходят под неё и растворяются. */
.ly__top {
  position: absolute;
  inset: 0 0 auto 0;
  z-index: 2;
  height: 88px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 20px 24px 0;
  background: linear-gradient(180deg, var(--ly-bg) 45%, transparent);
  pointer-events: none;
}
.ly__top > * {
  pointer-events: auto;
}
.ly__context {
  font-weight: 700;
  font-size: 16px;
  line-height: 36px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 60%;
}
a.ly__context:hover {
  text-decoration: underline;
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
  transition: background 0.15s ease, transform 0.15s ease;
}
.ly__btn:hover {
  color: #fff;
  background: rgba(0, 0, 0, 0.2);
  transform: scale(1.06);
}
.ly__scroll {
  position: absolute;
  inset: 0;
  overflow-y: auto;
  padding: 96px clamp(24px, 6cqw, 64px) 45%;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.35) transparent;
}
/* Колонка строк — по центру, не шире 1024px, как у оригинала. */
.ly__col {
  max-width: 1024px;
  margin: 0 auto;
}
.ly__notice {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.75);
  margin-bottom: 28px;
}
/* Размер и цвета сняты с живого Spotify: 48px/700, будущие строки — светлый
   оттенок фона, прошедшие — он же наполовину прозрачный, звучащая — белая. */
.ly__line {
  font-size: clamp(26px, 5.4cqw, 48px);
  font-weight: 700;
  letter-spacing: -0.01em;
  line-height: 1.22;
  margin-bottom: 0.6em;
  color: var(--ly-tint);
  cursor: pointer;
  width: fit-content;
  max-width: 100%;
  text-decoration: underline transparent;
  text-decoration-thickness: 0.06em;
  text-underline-offset: 0.12em;
  transition: color 0.3s ease, opacity 0.3s ease, text-decoration-color 0.15s ease, text-shadow 0.4s ease;
}
.ly__line--past {
  opacity: 0.5;
}
/* Звучащая строка не просто белая — слегка светится. */
.ly__line--active {
  color: #fff;
  text-shadow: 0 0 28px rgba(255, 255, 255, 0.28);
}
@media (hover: hover) and (pointer: fine) {
  .ly__line:not(.ly__line--static):hover {
    color: #fff;
    opacity: 1;
    text-decoration-color: currentColor;
  }
}
.ly__line--static {
  color: rgba(255, 255, 255, 0.88);
  cursor: default;
}
.ly__none {
  color: rgba(255, 255, 255, 0.8);
  font-size: 20px;
  font-weight: 700;
}
.ly__none--wait {
  animation: ly-wait 1.4s ease-in-out infinite;
}
@keyframes ly-wait {
  50% {
    opacity: 0.45;
  }
}
.ly__credit {
  margin-top: 32px;
  font-size: 13px;
  font-weight: 700;
  color: var(--ly-tint);
  opacity: 0.85;
}
/* Мобильный: на весь экран, поверх мини-плеера и навигации. */
@media (max-width: 768px) {
  .ly {
    position: fixed;
    inset: 0;
    border-radius: 0;
    z-index: 80;
  }
  .ly__top {
    height: 76px;
    padding: calc(12px + env(safe-area-inset-top, 0px)) 16px 0;
  }
  .ly__scroll {
    padding: calc(76px + env(safe-area-inset-top, 0px)) 20px 45vh;
  }
  .ly__line {
    font-size: 26px;
    margin-bottom: 16px;
  }
}
</style>
