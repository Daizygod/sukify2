<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import LyricsSyncButton from './LyricsSyncButton.vue'
import { tint } from '@/lib/color'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'
import { useLyrics } from '@/composables/useLyrics'
import { useLyricsFollow } from '@/composables/useLyricsFollow'

const player = usePlayerStore()
const ui = useUiStore()

const track = computed(() => player.currentTrack)
const bg = computed(() => track.value?.release?.colors?.background || '#3b3054')
const lineTint = computed(() => tint(bg.value))

// Текст трека: если найден — обложка уезжает влево, справа появляются строки.
const { lyrics, lines, activeIndex, hasLyrics } = useLyrics()
const listEl = ref(null)
// Увёл текст руками — он остаётся там, вернуться можно «Синхронизацией».
const { following, resync } = useLyricsFollow(listEl, activeIndex, {
  resetKey: () => player.currentTrack?.id,
})

function seekTo(line) {
  following.value = true
  player.seek(line.ms)
}

function close() {
  ui.fullscreenOpen = false
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
}

function onKey(e) {
  if (e.key === 'Escape') close()
}
function onFsChange() {
  // Вышли из браузерного fullscreen (Esc/F11) — закрываем оверлей.
  if (!document.fullscreenElement) ui.fullscreenOpen = false
}
onMounted(() => {
  // Как F11: сразу разворачиваемся на весь экран.
  document.documentElement.requestFullscreen?.().catch(() => {})
  window.addEventListener('keydown', onKey)
  document.addEventListener('fullscreenchange', onFsChange)
})
onUnmounted(() => {
  window.removeEventListener('keydown', onKey)
  document.removeEventListener('fullscreenchange', onFsChange)
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {})
})
</script>

<template>
  <div class="fs" :style="{ '--fs-bg': bg, '--fs-tint': lineTint }">
    <div class="fs__top">
      <span class="fs__context">{{ player.contextName || 'Сейчас играет' }}</span>
      <button class="fs__btn" title="Свернуть" @click="close">
        <Icon name="plus" :size="18" style="transform: rotate(45deg)" />
      </button>
    </div>

    <div v-if="track" class="fs__center" :class="{ 'fs__center--lyrics': hasLyrics, 'fs__center--paused': !player.isPlaying }">
      <div class="fs__coverwrap">
        <Transition name="coverfade" mode="out-in">
          <CoverImage :key="track.id" :cover="track.cover" :size="1000" class="fs__cover" />
        </Transition>
      </div>

      <div class="fs__lyricscol">
        <Transition name="lyrfade">
          <div v-if="hasLyrics" ref="listEl" class="fs__lyrics">
            <template v-if="lines.length">
              <p
                v-for="(l, i) in lines"
                :key="i"
                class="fs__line"
                :class="{ 'fs__line--past': i < activeIndex, 'fs__line--active': i === activeIndex }"
                :data-i="i"
                @click="seekTo(l)"
              >
                {{ l.text || '♪' }}
              </p>
            </template>
            <template v-else>
              <p v-for="(l, i) in lyrics.plain.split('\n')" :key="i" class="fs__line fs__line--static">
                {{ l || ' ' }}
              </p>
            </template>
          </div>
        </Transition>
        <LyricsSyncButton :show="!following && lines.length > 0" @sync="resync()" />
      </div>
    </div>
    <div v-else class="fs__center">
      <p class="muted">Включи что-нибудь — здесь будет красиво.</p>
    </div>
  </div>
</template>

<style scoped>
/* Регистрируем цвет фона как анимируемое свойство — тогда смена цвета обложки
   при переключении трека происходит плавным переливом, а не резким скачком. */
@property --fs-bg {
  syntax: '<color>';
  inherits: true;
  initial-value: #3b3054;
}
.fs {
  /* Поверх всего, включая верхнюю панель — остаётся только плеер снизу. */
  position: fixed;
  top: 0;
  bottom: var(--player-height);
  left: 0;
  right: 0;
  z-index: 60;
  /* Цвет обложки уходит в глубину к низу; поверх — зерно и мягкое
     «свечение» из-за обложки, чтобы фон не был плоской заливкой. */
  background-color: color-mix(in srgb, var(--fs-bg) 40%, #000);
  background-image: var(--grain),
    radial-gradient(120% 80% at 25% 40%, color-mix(in srgb, var(--fs-bg) 85%, #fff) 0%, transparent 60%),
    linear-gradient(180deg, var(--fs-bg) 0%, color-mix(in srgb, var(--fs-bg) 40%, #000) 100%);
  display: flex;
  flex-direction: column;
  padding: 24px 32px;
  overflow: hidden;
  transition: --fs-bg 0.7s ease;
  animation: fs-in 0.45s var(--ease-spotify);
}
@keyframes fs-in {
  from {
    opacity: 0;
    transform: scale(1.02);
  }
}
.fs__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.fs__context {
  font-weight: 700;
  font-size: 16px;
}
.fs__btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: rgba(255, 255, 255, 0.8);
}
.fs__btn:hover {
  color: #fff;
  background: rgba(0, 0, 0, 0.2);
  transform: scale(1.06);
}
/* Сетка из двух колонок: обложка | текст. Ширины во фракциях — тогда переход
   «текст появился/исчез» плавно двигает обложку из центра влево и обратно. */
.fs__center {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 1fr 0fr;
  align-items: center;
  gap: 0;
  transition: grid-template-columns 0.6s ease, gap 0.6s ease;
}
.fs__coverwrap {
  display: grid;
  place-items: center;
  min-width: 0;
}
.fs__cover {
  width: min(62vh, 640px);
  border-radius: 10px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.55);
  transition: width 0.6s ease, transform 0.6s cubic-bezier(0.34, 1.4, 0.64, 1), box-shadow 0.4s ease;
}
/* Пауза — обложка чуть «отступает» вглубь. */
.fs__center--paused .fs__cover {
  transform: scale(0.94);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.45);
}
/* Есть текст: обложка съезжает влево, строки справа. */
.fs__center--lyrics {
  grid-template-columns: 0.85fr 1.15fr;
  gap: clamp(32px, 6vw, 96px);
}
.fs__center--lyrics .fs__cover {
  width: min(52vh, 40vw, 560px);
}
/* «Линзовый» кроссфейд обложки при смене трека. */
.coverfade-enter-active,
.coverfade-leave-active {
  transition: opacity 0.45s ease, transform 0.45s ease, filter 0.45s ease;
}
.coverfade-enter-from {
  opacity: 0;
  transform: scale(1.1);
  filter: blur(16px);
}
.coverfade-leave-to {
  opacity: 0;
  transform: scale(0.92);
  filter: blur(16px);
}
/* Плавное появление/исчезновение колонки текста. */
.lyrfade-enter-active,
.lyrfade-leave-active {
  transition: opacity 0.5s ease;
}
.lyrfade-enter-from,
.lyrfade-leave-to {
  opacity: 0;
}
/* Колонка текста — опора для кнопки «Синхронизация» по её центру. */
.fs__lyricscol {
  position: relative;
  align-self: stretch;
  min-width: 0;
  min-height: 0;
}
.fs__lyrics {
  position: absolute;
  inset: 0;
  overflow-y: auto;
  /* Пустота сверху/снизу, чтобы активная строка держалась по центру. */
  padding: 34vh 8px 34vh 0;
  scrollbar-width: none;
  -webkit-mask-image: linear-gradient(180deg, transparent 0, #000 15%, #000 85%, transparent 100%);
  mask-image: linear-gradient(180deg, transparent 0, #000 15%, #000 85%, transparent 100%);
}
.fs__lyrics::-webkit-scrollbar {
  display: none;
}
/* Цвета как в окне текста: впереди — светлый оттенок фона, позади — он же
   бледнее, звучащая строка белая и слегка светится. */
.fs__line {
  font-size: clamp(24px, 2.6vw, 40px);
  font-weight: 800;
  letter-spacing: -0.01em;
  line-height: 1.3;
  margin-bottom: 20px;
  color: var(--fs-tint);
  cursor: pointer;
  width: fit-content;
  max-width: 100%;
  text-decoration: underline transparent;
  text-decoration-thickness: 0.06em;
  text-underline-offset: 0.12em;
  transition: color 0.3s ease, opacity 0.3s ease, text-decoration-color 0.15s ease, text-shadow 0.4s ease;
}
.fs__line:hover {
  color: #fff;
  opacity: 1;
  text-decoration-color: currentColor;
}
.fs__line--past {
  opacity: 0.5;
}
.fs__line--active {
  color: #fff;
  text-shadow: 0 0 28px rgba(255, 255, 255, 0.28);
}
.fs__line--static {
  color: rgba(255, 255, 255, 0.85);
  cursor: default;
}
</style>
