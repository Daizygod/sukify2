<script setup>
import { computed, ref, watch, nextTick } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import Marquee from './Marquee.vue'
import SaveButton from './SaveButton.vue'
import api from '@/lib/api'
import { tint } from '@/lib/color'
import { formatListeners } from '@/lib/format'
import { releaseRoute, contextRoute } from '@/lib/links'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'
import { useLibraryStore } from '@/stores/library'
import { useAuthStore } from '@/stores/auth'
import { useLyrics } from '@/composables/useLyrics'

/**
 * «Сейчас играет» справа — по образцу Spotify: шапка с играющей подборкой,
 * обложка, название (ведёт на альбом), превью текста в цвет обложки,
 * «Об исполнителе», «Сведения» и «Далее в очереди».
 */
const player = usePlayerStore()
const ui = useUiStore()
const library = useLibraryStore()
const auth = useAuthStore()
const router = useRouter()

const track = computed(() => player.currentTrack)
const artist = computed(() => track.value?.artists?.[0] || null)
const trackLink = computed(() => releaseRoute(track.value, player.contextKey))
const ctxLink = computed(() => contextRoute(player.contextKey))
const liked = computed(() => !!track.value && library.isLiked(track.value.id))
const color = computed(() => track.value?.release?.colors?.background || '#3b3054')

// Шапка отбрасывает тень, когда содержимое уехало под неё.
const scrolled = ref(false)
function onScroll(e) {
  scrolled.value = e.target.scrollTop > 4
}

// --- Превью текста: «лента» строк, звучащая всегда сверху окна ------------
const { lyrics, lines, activeIndex, hasLyrics } = useLyrics()
const previewLines = computed(() => {
  if (lines.value.length) return lines.value.map((l) => l.text || '♪')
  if (lyrics.value?.plain) return lyrics.value.plain.split('\n').filter((l) => l.trim())
  return []
})
const rollEl = ref(null)
const rollY = ref(0)
watch(
  () => [activeIndex.value, previewLines.value.length],
  async ([i]) => {
    await nextTick()
    const el = i > 0 ? rollEl.value?.querySelector(`[data-i="${i}"]`) : null
    rollY.value = el ? el.offsetTop : 0
  },
  { immediate: true },
)

// --- Об исполнителе ---------------------------------------------------------
const about = ref(null)
watch(
  () => artist.value?.slug,
  async (slug) => {
    about.value = null
    if (!slug) return
    try {
      const { data } = await api.get(`/artists/${slug}`)
      if (artist.value?.slug === slug) about.value = data.data
    } catch {}
  },
  { immediate: true },
)
const aboutImage = computed(() => about.value?.banner_url || about.value?.avatar_url || null)

function isFollowed(a) {
  if (about.value && about.value.id === a.id) return !!about.value.is_followed
  return library.followedArtists.some((x) => x.id === a.id)
}
async function toggleFollow(a) {
  if (!auth.isAuthenticated) return
  // Карточка «Об исполнителе» держит свой объект — переключаем именно его,
  // иначе кнопка в «Сведениях» и в карточке разъедутся.
  const target = about.value && about.value.id === a.id ? about.value : { ...a, is_followed: isFollowed(a) }
  await library.toggleFollow(target)
}

// --- Далее в очереди ----------------------------------------------------------
const nextTrack = computed(() => player.peekNext?.() || null)

function openArtist(slug) {
  router.push({ name: 'artist', params: { slug } })
}
</script>

<template>
  <aside class="np">
    <div class="np__head" :class="{ 'np__head--shadow': scrolled }">
      <RouterLink v-if="track && ctxLink && player.contextName" :to="ctxLink" class="np__title np__title--link">{{ player.contextName }}</RouterLink>
      <span v-else class="np__title">{{ track ? player.contextName || track.title : 'Сейчас играет' }}</span>
      <button class="np__close" title="Закрыть" @click="ui.toggleRight()"><Icon name="close" :size="16" /></button>
    </div>

    <div v-if="track" v-osbar class="np__body" @scroll.passive="onScroll">
      <Transition name="npc" mode="out-in">
        <div :key="track.id" class="np__coverwrap">
          <CoverImage :cover="track.cover" :size="640" class="np__cover" />
        </div>
      </Transition>

      <div class="np__meta">
        <div class="np__titles">
          <Marquee :watch-key="track.id" class="np__song">
            <RouterLink v-if="trackLink" :to="trackLink" class="np__songlink">{{ track.title }}</RouterLink>
            <span v-else>{{ track.title }}</span>
          </Marquee>
          <div class="np__artists">
            <template v-for="(a, i) in track.artists" :key="a.id">
              <RouterLink :to="{ name: 'artist', params: { slug: a.slug } }">{{ a.name }}</RouterLink><span v-if="i < track.artists.length - 1">, </span>
            </template>
          </div>
        </div>
        <SaveButton v-if="auth.isAuthenticated" class="np__save" :on="liked" :size="20" @toggle="library.toggleLike(track)" />
      </div>

      <!-- Превью текста: карточка в цвет обложки, строки едут за песней. -->
      <section
        v-if="hasLyrics && previewLines.length"
        class="np__card np__lyrics"
        :style="{ '--np-color': color, '--np-tint': tint(color) }"
        title="Открыть текст"
        @click="ui.lyricsOpen || ui.toggleLyrics()"
      >
        <div class="np__cardhead">
          <h3>Предпросмотр текста</h3>
          <Icon name="expand" :size="14" class="np__lyexpand" />
        </div>
        <div class="np__lywin">
          <div ref="rollEl" class="np__lyroll" :style="{ transform: `translateY(${-rollY}px)` }">
            <p
              v-for="(l, i) in previewLines"
              :key="i"
              :data-i="i"
              class="np__lyline"
              :class="{ on: lines.length && i === activeIndex, past: lines.length && i < activeIndex }"
            >
              {{ l }}
            </p>
          </div>
        </div>
      </section>

      <!-- Об исполнителе: фото, слушатели, подписка, кусочек биографии. -->
      <section v-if="about" class="np__card np__about" @click="openArtist(about.slug)">
        <div v-if="aboutImage" class="np__aboutimg">
          <img :src="aboutImage" alt="" />
          <span class="np__abouttag">Об исполнителе</span>
        </div>
        <div class="np__aboutbody">
          <!-- Фото нет — без пустой серой плашки, просто заголовок. -->
          <h3 v-if="!aboutImage" class="np__abouthead">Об исполнителе</h3>
          <div class="np__aboutname">{{ about.name }}</div>
          <div class="np__aboutrow">
            <span class="np__listeners">{{ formatListeners(about.monthly_listeners) }}</span>
            <button
              v-if="auth.isAuthenticated"
              class="np__follow"
              :class="{ on: about.is_followed }"
              @click.stop="toggleFollow(about)"
            >
              {{ about.is_followed ? 'Уже подписаны' : 'Подписаться' }}
            </button>
          </div>
          <p v-if="about.bio" class="np__bio">{{ about.bio }}</p>
        </div>
      </section>

      <!-- Сведения: кто на треке. -->
      <section v-if="track.artists?.length" class="np__card np__credits">
        <div class="np__cardhead">
          <h3>Сведения</h3>
        </div>
        <div v-for="(a, i) in track.artists" :key="a.id" class="np__credit">
          <div class="np__creditmeta">
            <RouterLink :to="{ name: 'artist', params: { slug: a.slug } }" class="np__creditname">{{ a.name }}</RouterLink>
            <div class="np__creditrole">{{ i === 0 ? 'Основной исполнитель' : 'Исполнитель' }}</div>
          </div>
          <button v-if="auth.isAuthenticated" class="np__follow np__follow--sm" :class="{ on: isFollowed(a) }" @click="toggleFollow(a)">
            {{ isFollowed(a) ? 'Уже подписаны' : 'Подписаться' }}
          </button>
        </div>
      </section>

      <!-- Далее в очереди: следующий трек и вход в очередь. -->
      <section v-if="nextTrack" class="np__card np__next">
        <div class="np__cardhead">
          <h3>Далее в очереди</h3>
          <button class="np__cardlink" @click="ui.openRight('queue')">Открыть очередь</button>
        </div>
        <button class="np__nextrow" title="Включить" @click="player.next()">
          <span class="np__nextcover">
            <CoverImage :cover="nextTrack.cover" :size="48" />
            <span class="np__nextplay"><Icon name="play" :size="14" /></span>
          </span>
          <span class="np__nextmeta">
            <span class="np__nexttitle">{{ nextTrack.title }}</span>
            <span class="np__nextartists">{{ (nextTrack.artists || []).map((a) => a.name).join(', ') }}</span>
          </span>
        </button>
      </section>
    </div>

    <div v-else class="np__empty">
      <div class="np__emptyart"><Icon name="nowplaying" :size="40" /></div>
      <p class="np__emptytitle">Сейчас ничего не играет</p>
      <p class="muted">Включи трек — здесь появятся обложка, текст и всё об исполнителе.</p>
    </div>
  </aside>
</template>

<style scoped>
.np {
  height: 100%;
  /* Не плоская заливка: сверху чуть светлее — панель «выпуклая», как у Spotify. */
  background: linear-gradient(180deg, #1c1c1c 0%, var(--bg-elevated) 220px);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}
.np__head {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 16px 16px 8px;
  transition: box-shadow 0.25s ease, background-color 0.25s ease;
}
.np__head--shadow {
  background-color: #181818;
  box-shadow: 0 6px 12px rgba(0, 0, 0, 0.55);
}
.np__title {
  font-weight: 700;
  font-size: 16px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
.np__title--link:hover {
  text-decoration: underline;
}
.np__close {
  flex: 0 0 32px;
  color: var(--text-subdued);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  transition: background 0.15s ease, color 0.15s ease, transform 0.15s var(--ease-spotify);
}
.np__close:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
  transform: scale(1.06);
}
.np__body {
  overflow-y: auto;
  padding: 8px 16px 16px;
}
.np__coverwrap {
  margin-bottom: 16px;
}
.np__cover {
  width: 100%;
  border-radius: 8px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.5);
}
/* Смена трека: обложка проявляется из размытия. */
.npc-enter-active {
  transition: opacity 0.4s ease, transform 0.45s var(--ease-spotify), filter 0.45s ease;
}
.npc-leave-active {
  transition: opacity 0.18s ease;
}
.npc-enter-from {
  opacity: 0;
  transform: scale(0.96);
  filter: blur(10px);
}
.npc-leave-to {
  opacity: 0;
}
.np__meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}
.np__titles {
  flex: 1;
  min-width: 0;
}
.np__song {
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.01em;
  line-height: 1.25;
}
.np__songlink:hover {
  text-decoration: underline;
}
.np__artists {
  color: var(--text-subdued);
  font-size: 16px;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.np__artists a:hover {
  color: #fff;
  text-decoration: underline;
}
.np__save {
  flex: 0 0 auto;
}
/* --- Карточки ------------------------------------------------------------ */
.np__card {
  position: relative;
  /* Объём: свет сверху, глубина к низу — серый градиент вместо плашки. */
  background: linear-gradient(180deg, #242424 0%, #1c1c1c 100%);
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  overflow: hidden;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
  transition: transform 0.2s var(--ease-spotify), box-shadow 0.2s ease, background-color 0.2s ease;
}
.np__cardhead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}
.np__cardhead h3 {
  font-size: 16px;
  font-weight: 700;
}
.np__cardlink {
  color: var(--text-subdued);
  font-size: 14px;
  font-weight: 700;
}
.np__cardlink:hover {
  color: #fff;
  text-decoration: underline;
}
/* Превью текста — в цвет обложки, с зерном, как живой Spotify. */
.np__lyrics {
  cursor: pointer;
  background-color: var(--np-color);
  background-image: var(--grain), linear-gradient(180deg, rgba(255, 255, 255, 0.06), rgba(0, 0, 0, 0.16));
  box-shadow: none;
}
.np__lyrics:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.35);
}
.np__lyexpand {
  opacity: 0;
  transition: opacity 0.15s ease;
}
.np__lyrics:hover .np__lyexpand {
  opacity: 1;
}
.np__lywin {
  position: relative;
  height: 132px;
  overflow: hidden;
  -webkit-mask-image: linear-gradient(180deg, #000 72%, transparent);
  mask-image: linear-gradient(180deg, #000 72%, transparent);
}
.np__lyroll {
  position: relative;
  transition: transform 0.55s var(--ease-spotify);
}
.np__lyline {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.25;
  margin-bottom: 8px;
  color: var(--np-tint);
  transition: color 0.3s ease, opacity 0.3s ease;
}
.np__lyline.on {
  color: #fff;
}
.np__lyline.past {
  opacity: 0.5;
}
/* Об исполнителе */
.np__about {
  padding: 0;
  cursor: pointer;
}
.np__about:hover {
  background: linear-gradient(180deg, #2a2a2a 0%, #202020 100%);
}
.np__aboutimg {
  position: relative;
  aspect-ratio: 16 / 10;
  overflow: hidden;
  background: #333;
}
.np__aboutimg img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.6s var(--ease-spotify);
}
.np__about:hover .np__aboutimg img {
  transform: scale(1.04);
}
.np__abouthead {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 12px;
}
.np__aboutimg::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.55) 0%, transparent 38%);
  pointer-events: none;
}
.np__abouttag {
  position: absolute;
  top: 16px;
  left: 16px;
  z-index: 1;
  font-weight: 700;
  font-size: 16px;
}
.np__aboutbody {
  padding: 16px;
}
.np__aboutname {
  font-size: 16px;
  font-weight: 700;
}
.np__aboutrow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
}
.np__listeners {
  color: var(--text-subdued);
  font-size: 14px;
}
.np__bio {
  margin-top: 12px;
  color: var(--text-subdued);
  font-size: 14px;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.np__follow {
  flex: 0 0 auto;
  border: 1px solid #7c7c7c;
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  transition: border-color 0.15s ease, transform 0.15s var(--ease-spotify);
}
.np__follow:hover {
  border-color: #fff;
  transform: scale(1.04);
}
.np__follow.on {
  border-color: #fff;
}
.np__follow--sm {
  padding: 4px 12px;
  font-size: 13px;
}
/* Сведения */
.np__credit {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.np__credit + .np__credit {
  margin-top: 14px;
}
.np__creditmeta {
  min-width: 0;
}
.np__creditname {
  font-size: 16px;
  display: block;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.np__creditname:hover {
  text-decoration: underline;
}
.np__creditrole {
  color: var(--text-subdued);
  font-size: 14px;
  margin-top: 2px;
}
/* Далее в очереди */
.np__nextrow {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px;
  margin: -8px;
  width: calc(100% + 16px);
  border-radius: 6px;
  text-align: left;
  transition: background 0.15s ease;
}
.np__nextrow:hover {
  background: rgba(255, 255, 255, 0.08);
}
.np__nextcover {
  position: relative;
  width: 48px;
  flex: 0 0 48px;
}
.np__nextplay {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: rgba(0, 0, 0, 0.5);
  border-radius: 4px;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.np__nextrow:hover .np__nextplay {
  opacity: 1;
}
.np__nextmeta {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.np__nexttitle {
  font-size: 16px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.np__nextartists {
  color: var(--text-subdued);
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.np__empty {
  padding: 40px 24px;
  text-align: center;
}
.np__emptyart {
  width: 88px;
  height: 88px;
  margin: 0 auto 16px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #b3b3b3;
  background: radial-gradient(circle at 30% 25%, #3b3b3b, #1a1a1a 70%);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.5), inset 0 1px 0 rgba(255, 255, 255, 0.06);
  animation: np-float 4s ease-in-out infinite;
}
@keyframes np-float {
  50% {
    transform: translateY(-6px);
  }
}
.np__emptytitle {
  font-weight: 700;
  font-size: 16px;
  margin-bottom: 6px;
}
</style>
