<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/lib/api'
import Icon from '../Icon.vue'
import CoverImage from '../CoverImage.vue'
import DragBar from '../DragBar.vue'
import { formatDuration, formatListeners } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useLibraryStore } from '@/stores/library'
import { useUiStore } from '@/stores/ui'
import { useMenuStore } from '@/stores/menu'
import { useAuthStore } from '@/stores/auth'
import { useLyrics } from '@/composables/useLyrics'

const player = usePlayerStore()
const library = useLibraryStore()
const ui = useUiStore()
const menu = useMenuStore()
const auth = useAuthStore()
const router = useRouter()

const track = computed(() => player.currentTrack)
const liked = computed(() => track.value && library.isLiked(track.value.id))
const bg = computed(() => track.value?.release?.colors?.background || '#3b3054')
const progress = computed(() => (player.durationMs ? Math.min(player.positionMs / player.durationMs, 1) : 0))

const { lyrics, lines, activeIndex, hasLyrics } = useLyrics()

// Карточка «Об исполнителе».
const aboutArtist = ref(null)
watch(
  () => track.value?.artists?.[0]?.slug,
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

function close() {
  ui.mobileNowOpen = false
}
function goArtist(slug) {
  close()
  router.push({ name: 'artist', params: { slug } })
}
function cycleRepeat() {
  player.repeat = player.repeat === 'off' ? 'all' : player.repeat === 'all' ? 'one' : 'off'
}
function openMenu(e) {
  if (track.value) menu.openMenu(e, track.value)
}
async function toggleFollow() {
  if (!auth.isAuthenticated || !aboutArtist.value) return
  await library.toggleFollow(aboutArtist.value)
}

// Превью текста в карточке — первые строки; активная подсвечивается.
const previewLines = computed(() => {
  if (lines.value.length) return lines.value.map((l) => l.text || '♪')
  if (lyrics.value?.plain) return lyrics.value.plain.split('\n')
  return []
})
</script>

<template>
  <div v-if="track" class="mnp" :style="{ '--mnp-bg': bg }">
    <div class="mnp__head">
      <button class="mnp__hbtn" @click="close"><Icon name="caretDown" :size="20" /></button>
      <span class="mnp__context">{{ player.contextName || 'Трек' }}</span>
      <button class="mnp__hbtn" @click="openMenu"><Icon name="more" :size="20" /></button>
    </div>

    <div class="mnp__scroll">
      <div class="mnp__coverwrap">
        <CoverImage :cover="track.cover" :size="1000" class="mnp__cover" />
      </div>

      <div class="mnp__titlerow">
        <div class="mnp__titles">
          <div class="mnp__title">{{ track.title }}</div>
          <div class="mnp__artists">
            <template v-for="(a, i) in track.artists" :key="a.id">
              <span @click="goArtist(a.slug)">{{ a.name }}</span><span v-if="i < track.artists.length - 1">, </span>
            </template>
          </div>
        </div>
        <button v-if="auth.isAuthenticated" class="mnp__like" :class="{ on: liked }" @click="library.toggleLike(track)">
          <Icon :name="liked ? 'checkCircleBig' : 'plusCircleBig'" :size="26" />
        </button>
      </div>

      <div class="mnp__progress">
        <DragBar :value="progress" @input="(f) => player.seek(f * player.durationMs)" />
        <div class="mnp__times">
          <span>{{ formatDuration(player.positionMs) }}</span>
          <span>{{ formatDuration(player.durationMs) }}</span>
        </div>
      </div>

      <div class="mnp__controls">
        <button class="mnp__ctl" :class="{ on: player.shuffle }" @click="player.setShuffle(!player.shuffle)">
          <Icon name="shuffle" :size="22" />
        </button>
        <button class="mnp__ctl mnp__ctl--big" @click="player.prev()"><Icon name="prev" :size="30" /></button>
        <button class="mnp__play" @click="player.togglePlay()">
          <Icon :name="player.isPlaying ? 'pauseBig' : 'playBig'" :size="30" />
        </button>
        <button class="mnp__ctl mnp__ctl--big" @click="player.next()"><Icon name="next" :size="30" /></button>
        <button class="mnp__ctl" :class="{ on: player.repeat !== 'off' }" @click="cycleRepeat">
          <Icon name="repeat" :size="22" />
          <span v-if="player.repeat === 'one'" class="mnp__one">1</span>
        </button>
      </div>

      <!-- Карточка «Текст» — как в мобильном Spotify -->
      <section v-if="hasLyrics" class="mnp__card mnp__card--lyrics">
        <div class="mnp__cardhead">
          <h3>Текст</h3>
          <button class="mnp__expand" @click="ui.lyricsOpen = true"><Icon name="expand" :size="16" /></button>
        </div>
        <div class="mnp__lyrics">
          <p
            v-for="(l, i) in previewLines"
            :key="i"
            class="mnp__line"
            :class="{ on: lines.length && i === activeIndex, past: lines.length && i < activeIndex }"
            @click="lines.length && player.seek(lines[i].ms)"
          >
            {{ l || ' ' }}
          </p>
        </div>
      </section>

      <!-- Карточка «Об исполнителе» -->
      <section v-if="aboutArtist" class="mnp__card mnp__card--artist" @click="goArtist(aboutArtist.slug)">
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
  display: grid;
  place-items: center;
  padding: 4vh 0 5vh;
}
.mnp__cover {
  width: min(100%, 42vh);
  border-radius: 8px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.5);
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
  margin: 10px 0 28px;
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
  overflow: hidden;
  -webkit-mask-image: linear-gradient(180deg, #000 70%, transparent 100%);
  mask-image: linear-gradient(180deg, #000 70%, transparent 100%);
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
