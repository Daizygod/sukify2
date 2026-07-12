<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/lib/api'
import Icon from '../Icon.vue'
import CoverImage from '../CoverImage.vue'
import DragBar from '../DragBar.vue'
import MobileQueueSheet from './MobileQueueSheet.vue'
import MobileDevicesSheet from './MobileDevicesSheet.vue'
import { formatDuration, formatListeners } from '@/lib/format'
import { useLibraryStore } from '@/stores/library'
import { useUiStore } from '@/stores/ui'
import { useMenuStore } from '@/stores/menu'
import { useAuthStore } from '@/stores/auth'
import { useLyrics } from '@/composables/useLyrics'
import { usePlaybackControls } from '@/composables/usePlaybackControls'

const library = useLibraryStore()
const ui = useUiStore()
const menu = useMenuStore()
const auth = useAuthStore()
const router = useRouter()

const {
  player, devices, remote, localTrack, view, hasPlayback,
  shownPlaying, shownDuration, shownPosition, shownProgress,
  shownShuffle, shownRepeat,
  togglePlay, next, prev, seek, toggleShuffle, cycleRepeat,
} = usePlaybackControls()

const liked = computed(() => localTrack.value && library.isLiked(localTrack.value.id))
const bg = computed(() =>
  remote.value ? '#503750' : localTrack.value?.release?.colors?.background || '#3b3054'
)

const { lyrics, lines, activeIndex, hasLyrics } = useLyrics()
const showCards = computed(() => !remote.value && !!localTrack.value)

// Живая строка текста под обложкой (как в приложении).
const liveLine = computed(() =>
  showCards.value && lines.value.length && activeIndex.value >= 0
    ? lines.value[activeIndex.value].text || '♪'
    : ''
)

// Соседние треки для карусели обложек.
const nextTrack = computed(() =>
  remote.value ? null : player.manualQueue[0] || player.upcoming[0] || null
)
const prevTrack = computed(() =>
  remote.value ? null : player.queue[player.queueIndex - 1] || null
)

// Карточка «Текст» следует за перемоткой: активная строка держится по центру.
const lyBox = ref(null)
watch(activeIndex, (i) => {
  if (i < 0 || !lyBox.value) return
  const line = lyBox.value.querySelector(`[data-i="${i}"]`)
  if (!line) return
  // Скроллим только контейнер карточки, не всю страницу.
  lyBox.value.scrollTo({ top: line.offsetTop - lyBox.value.clientHeight / 2 + line.clientHeight / 2, behavior: 'smooth' })
})

// Карточка «Об исполнителе».
const aboutArtist = ref(null)
watch(
  () => localTrack.value?.artists?.[0]?.slug,
  async (slug) => {
    aboutArtist.value = null
    if (!slug) return
    try {
      const { data } = await api.get(`/artists/${slug}`)
      aboutArtist.value = data.data
    } catch {}
  },
  { immediate: true }
)
const aboutImage = computed(() => aboutArtist.value?.banner_url || aboutArtist.value?.avatar_url || null)

// Свайп обложки: карусель — текущая уезжает, соседняя обложка въезжает следом.
const swipeX = ref(0)
const swiping = ref(false)
const settling = ref(false)
const stripEl = ref(null)
let touchX = 0
let touchY = 0
let horizontal = null
function onTouchStart(e) {
  if (settling.value) return
  touchX = e.touches[0].clientX
  touchY = e.touches[0].clientY
  horizontal = null
  swiping.value = true
}
function onTouchMove(e) {
  if (settling.value) return
  const dx = e.touches[0].clientX - touchX
  const dy = e.touches[0].clientY - touchY
  if (horizontal === null && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
    horizontal = Math.abs(dx) > Math.abs(dy)
  }
  if (!horizontal) return
  // Не даём утащить в сторону, где нет трека.
  let x = dx
  if (x < 0 && !nextTrack.value && !remote.value) x = Math.max(x, -30)
  if (x > 0 && !prevTrack.value && !remote.value) x = Math.min(x, 30)
  swipeX.value = x
}
function onTouchEnd() {
  swiping.value = false
  const dx = swipeX.value
  if (!horizontal) {
    swipeX.value = 0
    return
  }
  const w = stripEl.value?.clientWidth || 300
  const goNext = dx < -60 && (nextTrack.value || remote.value)
  const goPrev = dx > 60 && (prevTrack.value || remote.value)
  if (!goNext && !goPrev) {
    swipeX.value = 0
    return
  }
  // Доводим карусель до соседнего слайда и держим её там, пока трек
  // реально не сменится (locally мгновенно, на пульте — по бродкасту),
  // иначе обложка «отпрыгивает» назад до смены.
  settling.value = true
  swipeX.value = goNext ? -w : w
  setTimeout(() => (goNext ? next() : prev()), 230)
  // Страховка: трек не сменился (конец очереди и т.п.) — вернуть плавно.
  clearTimeout(settleGuard)
  settleGuard = setTimeout(() => {
    if (settling.value) {
      settling.value = false
      swipeX.value = 0
    }
  }, 4000)
}

// Как только показываемый трек сменился — мгновенно центрируем ленту
// (в центре уже новая обложка, боковые слайды пересчитались).
let settleGuard = null
const trackKey = computed(() =>
  remote.value ? devices.remoteState?.track?.id ?? view.value?.title : localTrack.value?.id
)
watch(trackKey, () => {
  if (!settling.value) return
  clearTimeout(settleGuard)
  settling.value = false
  swiping.value = true // выключаем transition на один кадр
  swipeX.value = 0
  requestAnimationFrame(() => (swiping.value = false))
})

// Нижние шиты.
const queueOpen = ref(false)
const devicesOpen = ref(false)

function close() {
  ui.mobileNowOpen = false
}
function goArtist(slug) {
  close()
  router.push({ name: 'artist', params: { slug } })
}
function openMenu(e) {
  if (localTrack.value) menu.openMenu(e, localTrack.value)
}
async function toggleFollow() {
  if (!auth.isAuthenticated || !aboutArtist.value) return
  await library.toggleFollow(aboutArtist.value)
}

// Превью текста в карточке — активная строка подсвечивается.
const previewLines = computed(() => {
  if (lines.value.length) return lines.value.map((l) => l.text || '♪')
  if (lyrics.value?.plain) return lyrics.value.plain.split('\n')
  return []
})
</script>

<template>
  <div v-if="hasPlayback" class="mnp" :style="{ '--mnp-bg': bg }">
    <div class="mnp__head">
      <button class="mnp__hbtn" @click="close"><Icon name="caretDown" :size="20" /></button>
      <span class="mnp__context">{{ remote ? devices.activeDevice?.name || 'Другое устройство' : player.contextName || 'Трек' }}</span>
      <button class="mnp__hbtn" :style="{ visibility: localTrack ? 'visible' : 'hidden' }" @click="openMenu"><Icon name="more" :size="20" /></button>
    </div>

    <div class="mnp__scroll">
      <div
        ref="stripEl"
        class="mnp__coverwrap"
        @touchstart.passive="onTouchStart"
        @touchmove.passive="onTouchMove"
        @touchend.passive="onTouchEnd"
      >
        <!-- Лента: предыдущая | текущая | следующая обложка -->
        <div class="mnp__strip" :class="{ 'mnp__strip--drag': swiping }" :style="{ transform: `translateX(calc(-100% + ${swipeX}px))` }">
          <div class="mnp__slide">
            <CoverImage v-if="prevTrack" :cover="prevTrack.cover" :size="1000" class="mnp__cover" />
          </div>
          <div class="mnp__slide">
            <img v-if="view.coverBigUrl" :src="view.coverBigUrl" class="mnp__cover mnp__cover--img" alt="" />
            <CoverImage v-else :cover="view.cover" :size="1000" class="mnp__cover" />
          </div>
          <div class="mnp__slide">
            <CoverImage v-if="nextTrack" :cover="nextTrack.cover" :size="1000" class="mnp__cover" />
          </div>
        </div>
      </div>

      <!-- Живая строка текста под обложкой, как в приложении -->
      <div v-if="liveLine" class="mnp__liveline">{{ liveLine }}</div>

      <div class="mnp__titlerow">
        <div class="mnp__titles">
          <div class="mnp__title">{{ view.title }}</div>
          <div class="mnp__artists">
            <template v-if="localTrack">
              <template v-for="(a, i) in localTrack.artists" :key="a.id">
                <span @click="goArtist(a.slug)">{{ a.name }}</span><span v-if="i < localTrack.artists.length - 1">, </span>
              </template>
            </template>
            <template v-else>{{ view.artists }}</template>
          </div>
        </div>
        <button v-if="auth.isAuthenticated && localTrack" class="mnp__like" :class="{ on: liked }" @click="library.toggleLike(localTrack)">
          <Icon :name="liked ? 'checkCircleBig' : 'plusCircleBig'" :size="26" />
        </button>
      </div>

      <div class="mnp__progress">
        <DragBar :value="shownProgress" @input="seek" />
        <div class="mnp__times">
          <span>{{ formatDuration(shownPosition) }}</span>
          <span>{{ formatDuration(shownDuration) }}</span>
        </div>
      </div>

      <div class="mnp__controls">
        <button class="mnp__ctl" :class="{ on: shownShuffle }" @click="toggleShuffle">
          <Icon name="shuffle" :size="22" />
        </button>
        <button class="mnp__ctl mnp__ctl--big" @click="prev"><Icon name="prev" :size="30" /></button>
        <button class="mnp__play" @click="togglePlay">
          <Icon :name="shownPlaying ? 'pauseBig' : 'playBig'" :size="30" />
        </button>
        <button class="mnp__ctl mnp__ctl--big" @click="next"><Icon name="next" :size="30" /></button>
        <button class="mnp__ctl" :class="{ on: shownRepeat !== 'off' }" @click="cycleRepeat">
          <Icon name="repeat" :size="22" />
          <span v-if="shownRepeat === 'one'" class="mnp__one">1</span>
        </button>
      </div>

      <!-- Устройства слева, очередь справа — как в приложении -->
      <div class="mnp__bottomrow">
        <button class="mnp__devbtn" :class="{ on: remote }" @click="devicesOpen = true">
          <Icon name="devices" :size="18" />
          <span v-if="remote">{{ devices.activeDevice?.name || 'Другое устройство' }}</span>
        </button>
        <button class="mnp__qbtn" @click="queueOpen = true">
          <Icon name="queue" :size="18" />
        </button>
      </div>

      <!-- Карточка «Текст» — как в мобильном Spotify -->
      <section v-if="showCards && hasLyrics" class="mnp__card mnp__card--lyrics">
        <div class="mnp__cardhead">
          <h3>Текст</h3>
          <button class="mnp__expand" @click="ui.lyricsOpen = true"><Icon name="expand" :size="16" /></button>
        </div>
        <div ref="lyBox" class="mnp__lyrics">
          <p
            v-for="(l, i) in previewLines"
            :key="i"
            class="mnp__line"
            :class="{ on: lines.length && i === activeIndex, past: lines.length && i < activeIndex }"
            :data-i="i"
            @click="lines.length && player.seek(lines[i].ms)"
          >
            {{ l || ' ' }}
          </p>
        </div>
      </section>

      <!-- Карточка «Об исполнителе» -->
      <section v-if="showCards && aboutArtist" class="mnp__card mnp__card--artist" @click="goArtist(aboutArtist.slug)">
        <div class="mnp__artimg">
          <img v-if="aboutImage" :src="aboutImage" alt="" />
          <div v-else class="mnp__artimg-ph"><Icon name="person" :size="48" /></div>
          <span class="mnp__abouttag">Об исполнителе</span>
        </div>
        <div class="mnp__artmeta">
          <div class="mnp__artname">{{ aboutArtist.name }}</div>
          <div class="mnp__artlisteners">{{ formatListeners(aboutArtist.monthly_listeners) }}</div>
          <button v-if="auth.isAuthenticated" class="mnp__follow" @click.stop="toggleFollow">
            {{ aboutArtist.is_followed ? 'Уже подписаны' : 'Подписаться' }}
          </button>
        </div>
      </section>
    </div>

    <MobileQueueSheet v-if="queueOpen" @close="queueOpen = false" />
    <MobileDevicesSheet v-if="devicesOpen" @close="devicesOpen = false" />
  </div>
</template>

<style scoped>
.mnp {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, var(--mnp-bg) 0%, color-mix(in srgb, var(--mnp-bg) 35%, #121212) 38%, #121212 72%);
  padding-top: env(safe-area-inset-top, 0);
}
.mnp__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 8px 4px;
}
.mnp__hbtn {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  color: #fff;
}
.mnp__context {
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  max-width: 60vw;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mnp__scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 24px calc(24px + env(safe-area-inset-bottom, 0px));
  scrollbar-width: none;
}
.mnp__scroll::-webkit-scrollbar {
  display: none;
}
.mnp__coverwrap {
  padding: 4vh 0 4vh;
  touch-action: pan-y;
  overflow: hidden;
}
/* Лента из трёх слайдов: transform -100% центрирует текущий. */
.mnp__strip {
  display: flex;
  width: 100%;
  transition: transform 0.24s ease;
  will-change: transform;
}
.mnp__strip--drag {
  transition: none;
}
.mnp__slide {
  flex: 0 0 100%;
  display: grid;
  place-items: center;
  padding: 0 6px;
}
.mnp__cover {
  width: min(100%, 42vh);
  border-radius: 8px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.5);
}
.mnp__cover--img {
  aspect-ratio: 1;
  object-fit: cover;
  width: min(78vw, 42vh);
}
/* Текущая строка текста под обложкой */
.mnp__liveline {
  font-size: 19px;
  font-weight: 700;
  line-height: 1.35;
  margin: -8px 0 20px;
  min-height: 26px;
}
.mnp__titlerow {
  display: flex;
  align-items: center;
  gap: 16px;
}
.mnp__titles {
  flex: 1;
  min-width: 0;
}
.mnp__title {
  font-size: 22px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mnp__artists {
  color: var(--text-subdued);
  font-size: 16px;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mnp__like {
  color: var(--text-subdued);
}
.mnp__like.on {
  color: var(--accent);
}
.mnp__progress {
  margin-top: 20px;
}
.mnp__times {
  display: flex;
  justify-content: space-between;
  color: rgba(255, 255, 255, 0.6);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  margin-top: 6px;
}
.mnp__controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 10px 0 4px;
}
.mnp__ctl {
  position: relative;
  color: var(--text-subdued);
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
}
.mnp__ctl--big {
  color: #fff;
}
.mnp__ctl.on {
  color: var(--accent);
}
.mnp__one {
  position: absolute;
  top: 2px;
  right: 4px;
  font-size: 10px;
  font-weight: 700;
}
.mnp__play {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: #fff;
  color: #000;
  display: grid;
  place-items: center;
}
.mnp__play:active {
  transform: scale(0.96);
}
/* Ряд «устройства + очередь» под контролами */
.mnp__bottomrow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 2px 0 24px;
}
.mnp__devbtn {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-subdued);
  font-size: 12px;
  font-weight: 700;
  padding: 8px 0;
  max-width: 70vw;
}
.mnp__devbtn.on {
  color: var(--accent);
}
.mnp__devbtn span {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mnp__qbtn {
  color: var(--text-subdued);
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
}
.mnp__qbtn:active,
.mnp__devbtn:active {
  color: #fff;
}
/* Карточки под контролами */
.mnp__card {
  border-radius: 10px;
  margin-bottom: 16px;
  overflow: hidden;
}
.mnp__card--lyrics {
  background: color-mix(in srgb, var(--mnp-bg) 80%, #fff 6%);
  padding: 16px;
}
.mnp__cardhead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.mnp__cardhead h3 {
  font-size: 16px;
  font-weight: 700;
}
.mnp__expand {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.3);
  color: #fff;
  display: grid;
  place-items: center;
}
.mnp__lyrics {
  max-height: 240px;
  overflow-y: auto;
  scrollbar-width: none;
  -webkit-mask-image: linear-gradient(180deg, #000 70%, transparent 100%);
  mask-image: linear-gradient(180deg, #000 70%, transparent 100%);
}
.mnp__lyrics::-webkit-scrollbar {
  display: none;
}
.mnp__line {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.4;
  margin-bottom: 8px;
  color: rgba(255, 255, 255, 0.55);
}
.mnp__line.on {
  color: #fff;
}
.mnp__line.past {
  color: rgba(0, 0, 0, 0.45);
}
.mnp__card--artist {
  background: #1f1f1f;
}
.mnp__artimg {
  position: relative;
  aspect-ratio: 16 / 10;
  overflow: hidden;
}
.mnp__artimg img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.mnp__artimg-ph {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  background: #333;
  color: var(--text-subdued);
}
.mnp__abouttag {
  position: absolute;
  top: 12px;
  left: 14px;
  font-size: 16px;
  font-weight: 700;
  text-shadow: 0 1px 8px rgba(0, 0, 0, 0.7);
}
.mnp__artmeta {
  padding: 14px;
  position: relative;
}
.mnp__artname {
  font-size: 18px;
  font-weight: 700;
}
.mnp__artlisteners {
  color: var(--text-subdued);
  font-size: 13px;
  margin-top: 4px;
}
.mnp__follow {
  position: absolute;
  right: 14px;
  top: 14px;
  border: 1px solid var(--text-muted);
  color: #fff;
  border-radius: 999px;
  padding: 5px 13px;
  font-size: 13px;
  font-weight: 700;
}
</style>
