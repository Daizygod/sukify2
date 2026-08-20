<script setup>
import { ref, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import draggable from 'vuedraggable'
import api from '@/lib/api'
import CollectionHero from '@/components/CollectionHero.vue'
import TrackRow from '@/components/TrackRow.vue'
import TransitionSpot from '@/components/TransitionSpot.vue'
import MixChip from '@/components/mix/MixChip.vue'
import Icon from '@/components/Icon.vue'
import PlayButton from '@/components/PlayButton.vue'
import { useTransitionInfo } from '@/lib/useTransitions'
import { trackCount, formatTotalDuration } from '@/lib/format'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'
import { useToastStore } from '@/stores/toasts'
import { useLibraryStore } from '@/stores/library'
import { downloadTracks } from '@/lib/download'
import HeroMenu from '@/components/HeroMenu.vue'
import PlaylistEditModal from '@/components/PlaylistEditModal.vue'

const route = useRoute()
const router = useRouter()
const player = usePlayerStore()
const ui = useUiStore()
const toasts = useToastStore()
const library = useLibraryStore()
const playlist = ref(null)
const items = ref([])
const loading = ref(true)

const isOwner = computed(() => playlist.value?.is_owner)

// Spotify tints the playlist hero with the cover palette — reuse the first
// track's release color, which the cover collage is built from.
const heroBg = computed(
  () => items.value[0]?.release?.colors?.background || '#535353'
)

const totalMs = computed(() => items.value.reduce((a, t) => a + (t.duration_ms || 0), 0))

const { info: tinfo, load: loadTinfo, keyFor } = useTransitionInfo()

const recs = ref([])

// --- Режим микса -----------------------------------------------------------
// «Создать микс» показывает темп и тональность каждого трека и ставит между
// строками чипы переходов.
const mixOn = computed(() => !!playlist.value?.mix_enabled)
const rowVariant = computed(() => (mixOn.value ? 'mix' : 'playlist'))
const mixBusy = ref(false)

async function toggleMix() {
  if (!playlist.value || mixBusy.value) return
  mixBusy.value = true
  const next = !mixOn.value
  try {
    const { data } = await api.put(`/playlists/${playlist.value.id}/mix`, { enabled: next })
    playlist.value = { ...playlist.value, mix_enabled: data.data.mix_enabled }
    if (!next) ui.closeMixEditor()
    else toasts.show('Микс включён: видны BPM, тональность и переходы')
  } catch (e) {
    toasts.show(e?.response?.data?.message || 'Не удалось переключить микс')
  } finally {
    mixBusy.value = false
  }
}

async function load(id) {
  loading.value = true
  try {
    const { data } = await api.get(`/playlists/${id}`)
    playlist.value = data.data
    items.value = data.data.tracks || []
    loadTinfo(items.value)
  } finally {
    loading.value = false
  }
  recs.value = []
  api.get(`/playlists/${id}/recommendations`).then(({ data }) => (recs.value = data.data)).catch(() => {})
}

async function addRec(t) {
  await library.addToPlaylist(playlist.value.id, t.id)
  recs.value = recs.value.filter((r) => r.id !== t.id)
  toasts.show(`«${t.title}» добавлен в плейлист`)
  await load(playlist.value.id)
}
watch(() => route.params.id, (id) => id && load(id), { immediate: true })

// Открыли инвайт-ссылку /playlist/{id}?join={token} — присоединяемся.
watch(
  () => route.query.join,
  async (token) => {
    if (!token || !route.params.id) return
    try {
      await api.post(`/playlists/${route.params.id}/join/${token}`)
      toasts.show('Теперь ты участник этого плейлиста!')
      library.refreshPlaylists()
      await load(route.params.id)
    } catch {
      toasts.show('Ссылка-приглашение не подошла')
    }
    router.replace({ query: {} })
  },
  { immediate: true }
)

const editOpen = ref(false)

// Треки добавили из правой панели — перечитываем состав.
watch(
  () => ui.playlistRevision,
  () => route.params.id && load(route.params.id)
)

const ctxKey = computed(() => `playlist:${route.params.id}`)
function playAll() {
  if (items.value.length) {
    player.playContext(items.value, 0, { name: playlist.value?.title, key: ctxKey.value })
  }
}

async function onReorder() {
  const ids = items.value.map((t) => t.playlist_item_id)
  await api.put(`/playlists/${playlist.value.id}/order`, { item_ids: ids })
  loadTinfo(items.value)
}

function playShuffled() {
  player.setShuffle(true)
  playAll()
}

const shareLink = computed(() =>
  playlist.value ? `${location.origin}/playlist/${playlist.value.id}` : location.origin
)

async function download() {
  toasts.show(`Скачиваю: ${items.value.length} трек(ов)…`)
  const n = await downloadTracks(items.value)
  toasts.show(`Скачано файлов: ${n}`)
}

/** Совместный режим: получить инвайт-токен и скопировать ссылку. */
async function invitePeople() {
  const { data } = await api.post(`/playlists/${playlist.value.id}/invite`)
  const url = `${location.origin}/playlist/${playlist.value.id}?join=${data.invite_token}`
  try {
    await navigator.clipboard.writeText(url)
    toasts.show('Ссылка-приглашение скопирована — отправь её другу')
  } catch {
    toasts.show(url)
  }
}

async function removePlaylist() {
  if (!confirm(`Удалить плейлист «${playlist.value.title}»?`)) return
  await api.delete(`/playlists/${playlist.value.id}`)
  await library.refreshPlaylists()
  toasts.show('Плейлист удалён')
  router.push('/')
}
</script>

<template>
  <div v-if="playlist" class="playlist">
    <CollectionHero
      :kind="playlist.is_collaborative ? 'Совместный плейлист' : playlist.is_public ? 'Открытый плейлист' : 'Закрытый плейлист'"
      :title="playlist.title"
      :cover="playlist.cover_url ? { 640: playlist.cover_url } : null"
      :bg="heroBg"
    >
      <template #meta>
        <div v-if="playlist.description" class="playlist__desc">{{ playlist.description }}</div>
        <div class="playlist__metaline">
          <strong>{{ playlist.owner?.name }}</strong>
          <span>• {{ trackCount(items.length) }},</span>
          <span class="muted">{{ formatTotalDuration(totalMs, true) }}</span>
        </div>
      </template>
    </CollectionHero>

    <PlaylistEditModal
      v-if="editOpen"
      :playlist="playlist"
      @close="editOpen = false"
      @saved="load(route.params.id)"
    />

    <div class="playlist__body" :style="{ '--body-bg': heroBg }">
      <div class="playlist__actions">
        <div class="playlist__actions-left">
          <PlayButton class="play-btn--lg" :context-key="ctxKey" :tracks="items" :name="playlist?.title || ''" />
          <button class="ctl-lg" :class="{ on: player.shuffle }" title="В случайном порядке" @click="playShuffled"><Icon name="shuffleBig" :size="32" /></button>
          <button class="ctl-lg" title="Скачать" @click="download"><Icon name="downloadCircle" :size="32" /></button>
          <HeroMenu :tracks="items" :link="shareLink" :can-delete="isOwner" :can-invite="isOwner" @delete="removePlaylist" @invite="invitePeople" />
        </div>
        <button class="playlist__view" @click="ui.toggleListCompact()">
          <span>{{ ui.listCompact ? 'Компактный' : 'Список' }}</span>
          <Icon name="list" :size="16" />
        </button>
      </div>

      <!-- Ряд правки — как в Spotify, под кнопкой ▶ -->
      <div v-if="isOwner || playlist.is_collaborator" class="playlist__edit">
        <button
          class="playlist__pill"
          :class="{ on: ui.rightView === 'add-tracks' && ui.rightOpen }"
          @click="ui.toggleAddTracks(playlist.id)"
        >
          <Icon name="plus" :size="14" />
          <span>Добавить</span>
        </button>
        <button
          v-if="isOwner"
          class="playlist__pill"
          :class="{ on: mixOn }"
          :disabled="mixBusy"
          @click="toggleMix"
        >
          <Icon name="sparkle" :size="14" />
          <span>{{ mixOn ? 'Микс включён' : 'Создать микс' }}</span>
        </button>
        <button v-if="isOwner" class="playlist__pill" @click="editOpen = true">
          <Icon name="edit" :size="14" />
          <span>Название и описание</span>
        </button>
      </div>

      <div class="tracklist" :class="{ 'tracklist--compact': ui.listCompact }">
        <div class="tracktable__head trackgrid" :class="`trackgrid--${rowVariant}`">
          <div>#</div>
          <div>Название</div>
          <template v-if="mixOn">
            <div>BPM</div>
            <div>Тональность</div>
          </template>
          <div>Альбом</div>
          <div>Дата добавления</div>
          <div class="th--right"><Icon name="clock" :size="16" /></div>
        </div>

        <draggable
          v-if="isOwner"
          v-model="items"
          item-key="playlist_item_id"
          handle=".pl-row"
          @end="onReorder"
        >
          <template #item="{ element, index }">
            <div class="pl-row">
              <TrackRow :track="element" :index="index" :variant="rowVariant" :context-tracks="items" :context-name="playlist.title" :context-key="ctxKey" />
              <MixChip
                v-if="mixOn && index < items.length - 1"
                :from="element"
                :to="items[index + 1]"
                :info="tinfo[keyFor(element, items[index + 1])]"
              />
              <TransitionSpot
                v-else-if="index < items.length - 1"
                :from="element"
                :to="items[index + 1]"
                :info="tinfo[keyFor(element, items[index + 1])]"
                @changed="loadTinfo(items)"
              />
            </div>
          </template>
        </draggable>

        <template v-else>
          <template v-for="(t, i) in items" :key="t.playlist_item_id">
            <TrackRow
              :track="t"
              :index="i"
              :variant="rowVariant"
              :context-tracks="items"
              :context-name="playlist.title"
              :context-key="ctxKey"
            />
            <MixChip
              v-if="mixOn && i < items.length - 1"
              :from="t"
              :to="items[i + 1]"
              :info="tinfo[keyFor(t, items[i + 1])]"
            />
            <TransitionSpot
              v-else-if="i < items.length - 1"
              :from="t"
              :to="items[i + 1]"
              :info="tinfo[keyFor(t, items[i + 1])]"
              @changed="loadTinfo(items)"
            />
          </template>
        </template>
      </div>

      <section v-if="recs.length && isOwner" class="playlist__recs">
        <h2 class="section-title">Рекомендуем</h2>
        <p class="muted playlist__recsub">На основе того, что уже есть в этом плейлисте</p>
        <div v-for="t in recs" :key="t.id" class="rec">
          <TrackRow :track="t" variant="playlist" :context-tracks="recs" context-name="Рекомендации" />
          <button class="rec__add" @click="addRec(t)">Добавить</button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.playlist__body {
  background-color: var(--body-bg, #222);
  background-image: linear-gradient(180deg, rgba(0, 0, 0, 0.6) 0, #121212 232px);
  padding: 24px;
  min-height: 400px;
}
.playlist__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.playlist__actions-left {
  display: flex;
  align-items: center;
  gap: 24px;
}
.play-btn--lg {
  width: 56px;
  height: 56px;
}
.ctl-lg {
  color: var(--text-subdued);
  display: grid;
  place-items: center;
}
.ctl-lg:hover {
  color: #fff;
  transform: scale(1.04);
}
/* Описание над строкой автора — как в Spotify. */
.playlist__desc {
  flex-basis: 100%;
  color: var(--text-subdued);
  font-size: 14px;
  margin-bottom: 6px;
}
.playlist__metaline {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.playlist__edit {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.playlist__pill {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #fff;
  background: rgba(255, 255, 255, 0.08);
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  padding: 8px 14px;
  line-height: 1;
}
.playlist__pill:hover {
  background: rgba(255, 255, 255, 0.16);
}
.playlist__pill.on {
  background: #fff;
  color: #000;
}
.playlist__view {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-subdued);
  font-size: 14px;
}
.playlist__view:hover {
  color: #fff;
}
.pl-row {
  cursor: grab;
}
.playlist__recs {
  margin-top: 40px;
}
.playlist__recsub {
  font-size: 14px;
  margin: -10px 0 12px;
}
.rec {
  position: relative;
}
.rec__add {
  position: absolute;
  right: 16px;
  top: 50%;
  translate: 0 -50%;
  border: 1px solid var(--text-muted);
  color: #fff;
  border-radius: 999px;
  padding: 5px 14px;
  font-size: 14px;
  font-weight: 700;
  background: var(--bg-elevated);
}
.rec__add:hover {
  border-color: #fff;
}
</style>
