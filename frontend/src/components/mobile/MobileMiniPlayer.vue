<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import Icon from '../Icon.vue'
import { useLibraryStore } from '@/stores/library'
import { useUiStore } from '@/stores/ui'
import { coverUrl } from '@/lib/cover'
import { usePlaybackControls } from '@/composables/usePlaybackControls'

const library = useLibraryStore()
const ui = useUiStore()

const { player, devices, remote, localTrack, view, hasPlayback, shownPlaying, shownProgress, togglePlay, next, prev } =
  usePlaybackControls()

// --- Содержимое плашки: лента из трёх карточек (пред | текущая | след) ------
// Сама плашка при свайпе стоит на месте — в приложении едет только содержимое,
// а фон переливается в цвет нового трека.
const prevTrack = computed(() => (remote.value ? null : player.queue[player.queueIndex - 1] || null))
const nextTrack = computed(() => (remote.value ? null : player.peekNext()))

function cardOf(track) {
  if (!track) return null
  return {
    title: track.title,
    artists: (track.artists || []).map((a) => a.name).join(', '),
    cover: coverUrl(track.cover, 160),
  }
}
const centerCard = computed(() => {
  if (!hasPlayback.value) return null
  if (remote.value) return { title: view.value.title, artists: view.value.artists, cover: view.value.coverUrl }
  return cardOf(localTrack.value)
})
const prevCard = computed(() => cardOf(prevTrack.value))
const nextCard = computed(() => cardOf(nextTrack.value))

/**
 * Ключ карточки — позиция в ленте, а не трек: после свайпа Vue переносит уже
 * отрисованный DOM-узел в центральный слот, вместо того чтобы менять текст и
 * src той карточке, которую зритель прямо сейчас видит.
 */
const pos = ref(0)
const cards = computed(() => [
  { key: pos.value - 1, data: prevCard.value },
  { key: pos.value, data: centerCard.value },
  { key: pos.value + 1, data: nextCard.value },
])

const canNext = computed(() => !!nextTrack.value || remote.value)
const canPrev = computed(() => !!prevTrack.value || remote.value)

// --- Жест ------------------------------------------------------------------
const miniX = ref(0)
const dragging = ref(false) // палец на экране — анимация выключена
const settling = ref(false) // лента доезжает до соседней карточки
const viewportEl = ref(null)
const stripEl = ref(null)
let sx = 0
let sy = 0
let horiz = null
let swiped = false
let pendingDir = null
let guard = null
let posMoved = false // позицию уже сдвинули сами — watch(trackKey) не трогает

function width() {
  return viewportEl.value?.clientWidth || 260
}
function onTouchStart(e) {
  if (settling.value) return
  sx = e.touches[0].clientX
  sy = e.touches[0].clientY
  horiz = null
  swiped = false
  dragging.value = true
}
function onTouchMove(e) {
  if (settling.value) return
  const dx = e.touches[0].clientX - sx
  const dy = e.touches[0].clientY - sy
  if (horiz === null && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) horiz = Math.abs(dx) > Math.abs(dy)
  if (!horiz) return
  // Сопротивление там, где соседнего трека нет.
  let x = dx
  if (x < 0 && !canNext.value) x = Math.max(x, -40)
  if (x > 0 && !canPrev.value) x = Math.min(x, 40)
  miniX.value = x
}
function onTouchEnd() {
  dragging.value = false
  const dx = miniX.value
  if (!horiz) {
    miniX.value = 0
    return
  }
  swiped = Math.abs(dx) > 12 // подавляем клик-открытие после свайпа
  const toNext = dx < -56 && canNext.value
  const toPrev = dx > 56 && canPrev.value
  if (!toNext && !toPrev) {
    miniX.value = 0 // не дотянул — плавно назад
    return
  }
  pendingDir = toNext ? 'next' : 'prev'
  settling.value = true
  miniX.value = toNext ? -width() : width()
  if (remote.value) toNext ? goNext() : goPrev()
  clearTimeout(guard)
  guard = setTimeout(reset, 3000) // трек не сменился — вернуть ленту на место
}

// Доводка закончилась: меняем трек и мгновенно центрируем ленту. Карточка,
// которая уже стоит перед глазами, просто переезжает в центральный слот.
function onStripEnd(e) {
  if (e.target !== stripEl.value || !settling.value || !pendingDir) return
  const dir = pendingDir
  pendingDir = null
  if (remote.value) return // команда уже ушла, ждём бродкаст
  dir === 'next' ? goNext() : goPrev()
  recenter()
}
function goNext() {
  posMoved = true
  pos.value++
  next()
}
// Свайп всегда уходит на предыдущий трек, даже если текущий играет дольше
// трёх секунд (кнопка ⏮ в большом плеере в этом случае его перезапускает).
function goPrev() {
  if (canPrev.value) {
    posMoved = true
    pos.value--
  }
  prev(true)
}
function recenter() {
  clearTimeout(guard)
  dragging.value = true // выключаем transition на кадр
  miniX.value = 0
  settling.value = false
  nextTick(() => requestAnimationFrame(() => (dragging.value = false)))
}
function reset() {
  pendingDir = null
  settling.value = false
  miniX.value = 0
}

// На пульте трек меняется не мгновенно, а по бродкасту — центрируем ленту
// тогда, иначе она осталась бы сдвинутой до срабатывания страховки.
const trackKey = computed(() =>
  remote.value ? devices.remoteState?.track?.id ?? view.value?.title : localTrack.value?.id
)
watch(trackKey, () => {
  // Трек доиграл сам — сдвигаем позицию, чтобы уже отрисованная правая
  // карточка стала центральной, а не перерисовывалась на месте.
  if (posMoved) posMoved = false
  else pos.value++
  if (settling.value) recenter()
})

function onOpen() {
  if (swiped) {
    swiped = false
    return
  }
  ui.mobileNowOpen = true
}

const liked = computed(() => localTrack.value && library.isLiked(localTrack.value.id))
const bg = computed(() =>
  remote.value ? '#503750' : localTrack.value?.release?.colors?.background || '#3a3a3a'
)
const deviceLabel = computed(() =>
  remote.value ? `Играет: ${devices.activeDevice?.name || 'другое устройство'}` : 'Sukify Web Player'
)
</script>

<template>
  <div
    v-if="hasPlayback"
    class="mini"
    :style="{ '--mini-bg': bg }"
    @click="onOpen"
    @touchstart.passive="onTouchStart"
    @touchmove.passive="onTouchMove"
    @touchend.passive="onTouchEnd"
  >
    <div ref="viewportEl" class="mini__viewport">
      <div
        ref="stripEl"
        class="mini__strip"
        :class="{ 'mini__strip--drag': dragging }"
        :style="{ transform: `translateX(calc(-100% + ${miniX}px))` }"
        @transitionend="onStripEnd"
      >
        <div v-for="c in cards" :key="c.key" class="mini__card">
          <template v-if="c.data">
            <img v-if="c.data.cover" :src="c.data.cover" class="mini__cover" alt="" />
            <div v-else class="mini__cover mini__cover--ph"></div>
            <div class="mini__meta">
              <div class="mini__line">
                <span class="mini__title">{{ c.data.title }}</span>
                <span class="mini__sep"> • </span>
                <span class="mini__artists">{{ c.data.artists }}</span>
              </div>
              <div class="mini__device" :class="{ 'mini__device--remote': remote }">
                <Icon v-if="remote" name="devices" :size="11" />
                {{ deviceLabel }}
              </div>
            </div>
          </template>
        </div>
      </div>
    </div>

    <button v-if="localTrack" class="mini__btn" :class="{ on: liked }" @click.stop="library.toggleLike(localTrack)">
      <Icon :name="liked ? 'checkCircleBig' : 'plusCircleBig'" :size="24" />
    </button>
    <button class="mini__btn mini__play" @click.stop="togglePlay">
      <Icon :name="shownPlaying ? 'pauseBig' : 'playBig'" :size="24" />
    </button>
    <div class="mini__bar">
      <div class="mini__fill" :style="{ width: shownProgress * 100 + '%' }"></div>
    </div>
  </div>
</template>

<style scoped>
.mini {
  position: fixed;
  left: 8px;
  right: 8px;
  /* Над нижней навигацией (56px) */
  bottom: calc(58px + env(safe-area-inset-bottom, 0px));
  z-index: 49;
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 8px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--mini-bg) 60%, #181818);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
  overflow: hidden;
  touch-action: pan-y;
  /* Плашка стоит на месте: при смене трека переливается только цвет. */
  transition: background 0.45s ease;
}
/* Окно ленты: соседние карточки живут за его краями. */
.mini__viewport {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  align-self: stretch;
  display: flex;
  align-items: center;
}
.mini__strip {
  display: flex;
  width: 100%;
  transition: transform 0.24s ease;
  will-change: transform;
}
.mini__strip--drag {
  transition: none;
}
.mini__card {
  flex: 0 0 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  /* Просвет между соседними карточками, чтобы они не слипались в жесте. */
  padding-right: 10px;
}
.mini__cover {
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  border-radius: 4px;
  object-fit: cover;
}
.mini__cover--ph {
  background: #333;
}
.mini__meta {
  flex: 1;
  min-width: 0;
}
.mini__line {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
}
.mini__title {
  color: #fff;
  font-weight: 700;
}
.mini__sep,
.mini__artists {
  color: rgba(255, 255, 255, 0.7);
}
.mini__device {
  font-size: 11px;
  color: var(--accent);
  margin-top: 1px;
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  overflow: hidden;
}
.mini__btn {
  color: #fff;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  flex: 0 0 40px;
}
.mini__btn.on {
  color: var(--accent);
}
.mini__bar {
  position: absolute;
  left: 8px;
  right: 8px;
  bottom: 2px;
  height: 2px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.25);
}
.mini__fill {
  height: 100%;
  border-radius: 2px;
  background: #fff;
}
</style>
