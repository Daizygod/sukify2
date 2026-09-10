<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import api from '@/lib/api'
import Icon from '@/components/Icon.vue'
import { useAuthStore } from '@/stores/auth'
import { useLibraryStore } from '@/stores/library'
import { useToastStore } from '@/stores/toasts'
import { formatNumber, plural } from '@/lib/format'

const auth = useAuthStore()
const library = useLibraryStore()
const toasts = useToastStore()

const dragOver = ref(false)
const uploading = ref(false)
const uploadPct = ref(0)
const error = ref('')

/** Текущий (или последний) разбор архива — им живёт вся страница. */
const job = ref(null)
let timer = null

/** Результат старого пути: список лайков из Exportify-CSV. */
const csvResult = ref(null)
const csvBusy = ref(false)

const running = computed(() => ['pending', 'parsing', 'enriching'].includes(job.value?.status))
const enriching = computed(() => job.value?.status === 'enriching')
const enrichPct = computed(() => {
  const total = job.value?.enrich_total || 0
  return total ? Math.round((job.value.enrich_done / total) * 100) : 0
})

const lib = computed(() => job.value?.summary?.library || null)
const history = computed(() => job.value?.summary?.history || null)
const historyYears = computed(() => {
  const h = history.value
  if (!h?.from || !h?.to) return ''
  const from = h.from.slice(0, 4)
  const to = h.to.slice(0, 4)
  return from === to ? from : `${from}–${to}`
})

onMounted(async () => {
  try {
    const { data } = await api.get('/import/spotify')
    job.value = data
    if (running.value) poll()
  } catch {
    /* страница работает и без прошлых импортов */
  }
})
onBeforeUnmount(() => clearTimeout(timer))

function poll() {
  clearTimeout(timer)
  timer = setTimeout(async () => {
    try {
      const { data } = await api.get(`/import/spotify/${job.value.id}`)
      const wasRunning = running.value
      job.value = data
      if (running.value) {
        poll()
      } else if (wasRunning) {
        library.load().catch(() => {})
        if (data.status === 'done') toasts.show('Импорт из Spotify готов')
      }
    } catch {
      poll()
    }
  }, 2000)
}

async function handleFile(file) {
  error.value = ''
  csvResult.value = null

  if (file.name.toLowerCase().endsWith('.csv')) {
    await importCsv(file)
    return
  }

  uploading.value = true
  uploadPct.value = 0
  try {
    const form = new FormData()
    form.append('archive', file)
    const { data } = await api.post('/import/spotify', form, {
      onUploadProgress: (e) => {
        uploadPct.value = Math.round((e.loaded / (e.total || e.loaded || 1)) * 100)
      },
    })
    job.value = data
    poll()
  } catch (e) {
    error.value =
      e?.response?.data?.message ||
      'Не смог принять файл. Нужен ZIP из «Download your data» (или CSV из Exportify).'
  } finally {
    uploading.value = false
  }
}

async function cancelEnrich() {
  try {
    const { data } = await api.post(`/import/spotify/${job.value.id}/cancel`)
    job.value = data
    clearTimeout(timer)
  } catch {
    /* уже завершилось */
  }
}

function onDrop(e) {
  dragOver.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) handleFile(file)
}
function onPick(e) {
  const file = e.target.files?.[0]
  if (file) handleFile(file)
  e.target.value = ''
}

// --- Старый путь: Exportify-CSV разбирается прямо в браузере ---------------

/** Простой CSV-парсер с поддержкой кавычек (формат Exportify). */
function parseCsv(text) {
  const rows = []
  let row = []
  let cell = ''
  let inQuotes = false
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (inQuotes) {
      if (ch === '"' && text[i + 1] === '"') { cell += '"'; i++ }
      else if (ch === '"') inQuotes = false
      else cell += ch
    } else if (ch === '"') inQuotes = true
    else if (ch === ',') { row.push(cell); cell = '' }
    else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text[i + 1] === '\n') i++
      row.push(cell); cell = ''
      if (row.some((c) => c !== '')) rows.push(row)
      row = []
    } else cell += ch
  }
  if (cell !== '' || row.length) { row.push(cell); if (row.some((c) => c !== '')) rows.push(row) }
  return rows
}

async function importCsv(file) {
  csvBusy.value = true
  try {
    const rows = parseCsv(await file.text())
    const head = (rows[0] || []).map((h) => h.toLowerCase())
    const col = (names) => head.findIndex((h) => names.some((n) => h.includes(n)))
    const iTitle = col(['track name', 'название трека'])
    const iArtists = col(['artist name', 'исполнител'])
    const iDur = col(['duration'])
    const items = iTitle === -1 ? [] : rows.slice(1).map((r) => ({
      title: r[iTitle] || '',
      artists: (r[iArtists] || '').split(/[,;]/).map((s) => s.trim()).filter(Boolean),
      duration_ms: iDur !== -1 ? parseInt(r[iDur]) || null : null,
    })).filter((x) => x.title)

    if (!items.length) {
      error.value = 'Не нашёл треков в CSV. Это точно выгрузка Exportify?'
      return
    }

    const { data } = await api.post('/import/liked', { items: items.slice(0, 5000) })
    csvResult.value = data
    library.load().catch(() => {})
    toasts.show(`Импорт готов: добавлено ${data.added}`)
  } catch {
    error.value = 'Не смог разобрать CSV.'
  } finally {
    csvBusy.value = false
  }
}
</script>

<template>
  <div class="content-pad imp">
    <h1 class="imp__title">Импорт из Spotify</h1>
    <p class="muted imp__lead">
      Забрасывай сюда архив «Download your data» целиком — Sukify заведёт твоих артистов,
      альбомы и треки, перенесёт «Любимые», плейлисты и все годы прослушиваний,
      а обложки и 30-секундные превью догрузит из Deezer.
    </p>

    <div class="imp__cols">
      <section class="imp__how">
        <h2>Как получить архив</h2>
        <ol>
          <li>Открой <a href="https://www.spotify.com/account/privacy/" target="_blank" rel="noopener">spotify.com/account/privacy</a>.</li>
          <li>Отметь <b>«Данные аккаунта»</b> — это библиотека, плейлисты и год истории.</li>
          <li>Ниже отметь <b>«Расширенная история прослушиваний»</b>, если хочешь всю статистику с самого начала.</li>
          <li>Подтверди запрос письмом. Первый архив приходит за пару дней, расширенный — до месяца.</li>
          <li>Пришедший ZIP закинь сюда, распаковывать не надо →</li>
        </ol>

        <h2>Что попадёт в Sukify</h2>
        <ul class="imp__list">
          <li><b>Любимые треки</b> — в том же порядке, что и в Spotify.</li>
          <li><b>Плейлисты</b> с описаниями и датами добавления.</li>
          <li><b>Сохранённые альбомы</b> и <b>подписки на артистов</b>.</li>
          <li><b>История прослушиваний</b> — она питает страницу <RouterLink to="/stats/spotify">«Твой Spotify»</RouterLink>.</li>
          <li>Локальные файлы из библиотеки — с бейджем «не на площадках».</li>
        </ul>

        <p class="muted imp__note">
          Аудио у импортированных треков — 30-секундное превью Deezer: полные версии
          Spotify не отдаёт никому. Залей файл через админку — превью заменится
          настоящим треком. Архив <b>Technical Log Information</b> можно не присылать,
          в нём только телеметрия плеера.
        </p>

        <details class="imp__csv">
          <summary>Нужны только лайки и ждать архив некогда</summary>
          <ol>
            <li>Открой <a href="https://exportify.net" target="_blank" rel="noopener">exportify.net</a> → <b>Get Started</b>.</li>
            <li>Напротив <b>Liked Songs</b> нажми <b>Export</b> — скачается CSV.</li>
            <li>Закинь CSV сюда. Этот путь ничего не создаёт: он лайкает только то, что уже есть в каталоге.</li>
          </ol>
        </details>
      </section>

      <section
        class="imp__drop"
        :class="{ over: dragOver, busy: uploading || csvBusy }"
        @dragover.prevent="dragOver = true"
        @dragleave="dragOver = false"
        @drop.prevent="onDrop"
      >
        <template v-if="uploading">
          <p class="imp__dropline">Загружаю архив… {{ uploadPct }}%</p>
          <div class="imp__bar"><div class="imp__barfill" :style="{ width: uploadPct + '%' }" /></div>
        </template>
        <template v-else-if="csvBusy">
          <p class="imp__dropline">Читаю CSV…</p>
        </template>
        <template v-else>
          <Icon name="install" :size="40" class="imp__dropicon" />
          <p class="imp__dropline">Перетащи сюда ZIP из Spotify</p>
          <label class="btn-primary imp__pick">
            Выбрать файл
            <input type="file" accept=".zip,.json,.csv" hidden @change="onPick" />
          </label>
          <p class="muted imp__hint">.zip целиком, или отдельный YourLibrary.json / CSV из Exportify</p>
        </template>
      </section>
    </div>

    <p v-if="error" class="imp__error">{{ error }}</p>

    <!-- Идёт разбор -->
    <section v-if="running" class="imp__panel">
      <h2 class="imp__panelhead">
        <span class="imp__spinner" />
        {{ job.stage || 'Работаю…' }}
      </h2>

      <div class="imp__bar"><div class="imp__barfill" :style="{ width: (job.progress || 0) + '%' }" /></div>

      <template v-if="enriching">
        <p class="muted imp__panelnote">
          Библиотека и плейлисты уже на месте — можно листать прямо сейчас.
          Обложки и превью приезжают по ходу: {{ formatNumber(job.enrich_done) }}
          из {{ formatNumber(job.enrich_total) }}.
        </p>
        <div class="imp__bar imp__bar--thin"><div class="imp__barfill" :style="{ width: enrichPct + '%' }" /></div>
        <div class="imp__actions">
          <RouterLink to="/liked" class="btn-secondary">Открыть «Любимые»</RouterLink>
          <button type="button" class="imp__linkbtn" @click="cancelEnrich">Остановить догрузку</button>
        </div>
      </template>
    </section>

    <!-- Разбор упал -->
    <section v-else-if="job?.status === 'failed'" class="imp__panel imp__panel--bad">
      <h2 class="imp__panelhead"><Icon name="warning" :size="18" /> Импорт сорвался</h2>
      <p class="muted imp__panelnote">{{ job.error || 'Не удалось разобрать архив.' }}</p>
    </section>

    <!-- Готово -->
    <section v-else-if="job && job.summary" class="imp__panel">
      <h2 class="imp__panelhead">
        <Icon name="check" :size="18" class="imp__ok" />
        {{ job.status === 'canceled' ? 'Импорт остановлен' : (lib || history ? 'Перенесено' : 'Разобрал архив') }}
        <span class="muted imp__file">{{ job.original_name }}</span>
      </h2>

      <div v-if="lib" class="imp__stats">
        <div class="imp__stat">
          <div class="imp__num imp__num--green">{{ formatNumber(lib.liked_tracks) }}</div>
          <div class="muted">в «Любимых»</div>
        </div>
        <div class="imp__stat">
          <div class="imp__num">{{ formatNumber(lib.playlists) }}</div>
          <div class="muted">{{ plural(lib.playlists, 'плейлист', 'плейлиста', 'плейлистов') }}</div>
        </div>
        <div class="imp__stat">
          <div class="imp__num">{{ formatNumber(lib.tracks_created) }}</div>
          <div class="muted">новых треков в каталоге</div>
        </div>
        <div class="imp__stat">
          <div class="imp__num">{{ formatNumber(lib.artists_created) }}</div>
          <div class="muted">{{ plural(lib.artists_created, 'артист', 'артиста', 'артистов') }}</div>
        </div>
        <div class="imp__stat">
          <div class="imp__num">{{ formatNumber(lib.releases_created) }}</div>
          <div class="muted">{{ plural(lib.releases_created, 'альбом', 'альбома', 'альбомов') }}</div>
        </div>
        <div class="imp__stat">
          <div class="imp__num">{{ formatNumber(lib.followed_artists) }}</div>
          <div class="muted">подписок</div>
        </div>
      </div>

      <p v-if="history && history.plays" class="imp__panelnote">
        История: <b>{{ formatNumber(history.plays) }}</b>
        {{ plural(history.plays, 'прослушивание', 'прослушивания', 'прослушиваний') }}
        за {{ historyYears }} —
        <b>{{ formatNumber(Math.round(history.ms / 3600000)) }}</b>
        {{ plural(Math.round(history.ms / 3600000), 'час', 'часа', 'часов') }} музыки.
        <span v-if="history.duplicates" class="muted">
          Ещё {{ formatNumber(history.duplicates) }} строк уже были из другого архива.
        </span>
      </p>

      <p v-if="job.summary?.note" class="muted imp__panelnote">{{ job.summary.note }}</p>

      <p v-if="lib?.playlists_skipped" class="muted imp__panelnote">
        {{ lib.playlists_skipped }}
        {{ plural(lib.playlists_skipped, 'плейлист пропущен', 'плейлиста пропущено', 'плейлистов пропущено') }}:
        одноимённые уже были собраны здесь вручную.
      </p>

      <div class="imp__actions">
        <RouterLink to="/liked" class="btn-primary">Любимые треки</RouterLink>
        <RouterLink v-if="history && history.plays" to="/stats/spotify" class="btn-secondary">Твой Spotify</RouterLink>
      </div>
    </section>

    <!-- Итог старого CSV-пути -->
    <section v-if="csvResult" class="imp__panel">
      <h2 class="imp__panelhead">Импорт лайков из CSV</h2>
      <div class="imp__stats">
        <div class="imp__stat">
          <div class="imp__num imp__num--green">{{ csvResult.added }}</div>
          <div class="muted">добавлено</div>
        </div>
        <div class="imp__stat">
          <div class="imp__num">{{ csvResult.already }}</div>
          <div class="muted">уже были</div>
        </div>
        <div class="imp__stat">
          <div class="imp__num" :class="{ 'imp__num--red': csvResult.missing.length }">{{ csvResult.missing.length }}</div>
          <div class="muted">нет в каталоге</div>
        </div>
      </div>
      <p class="muted imp__panelnote">
        Чтобы перенести и остальное — загрузи ZIP из «Download your data»: он заводит
        недостающие треки сам<template v-if="auth.isAdmin">, а полное аудио можно долить через
        <RouterLink to="/admin" class="imp__adminlink">админку</RouterLink></template>.
      </p>
    </section>
  </div>
</template>

<style scoped>
.imp__title {
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.02em;
}
.imp__lead {
  margin: 10px 0 28px;
  max-width: 720px;
  line-height: 1.5;
}
/* Ширину задаёт не окно, а колонка контента: справа может быть открыта
   панель «Сейчас играет», и обычная медиазапросная вёрстка про неё не знает. */
.imp {
  container-type: inline-size;
}
.imp__cols {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 32px;
  align-items: start;
}
@container (max-width: 780px) {
  .imp__cols {
    grid-template-columns: 1fr;
  }
  .imp__drop {
    position: static;
  }
}
.imp__how h2 {
  font-size: 17px;
  font-weight: 700;
  margin: 22px 0 10px;
}
.imp__how h2:first-child {
  margin-top: 0;
}
.imp__how ol,
.imp__list {
  margin: 0 0 8px 20px;
  color: var(--text-subdued);
  font-size: 14px;
  line-height: 1.9;
}
.imp__list {
  list-style: disc;
}
.imp__how a {
  color: var(--accent);
  text-decoration: underline;
}
.imp__how b {
  color: #fff;
}
.imp__note {
  font-size: 14px;
  line-height: 1.6;
  margin-top: 16px;
  max-width: 620px;
}
.imp__csv {
  margin-top: 18px;
  font-size: 14px;
  color: var(--text-subdued);
}
.imp__csv summary {
  cursor: pointer;
  color: #fff;
  font-weight: 600;
}
.imp__csv ol {
  margin-top: 10px;
}
.imp__drop {
  border: 2px dashed rgba(255, 255, 255, 0.25);
  border-radius: 12px;
  padding: 40px 24px;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  transition: border-color 0.15s ease, background 0.15s ease;
  position: sticky;
  top: 16px;
}
.imp__drop.over {
  border-color: var(--accent);
  background: rgba(30, 215, 96, 0.06);
}
.imp__drop.busy {
  border-style: solid;
}
.imp__dropicon {
  color: var(--text-subdued);
}
.imp__dropline {
  font-weight: 700;
  font-size: 16px;
}
.imp__pick {
  cursor: pointer;
  padding: 10px 24px;
  font-size: 14px;
}
.imp__hint {
  font-size: 12px;
  line-height: 1.5;
}
.imp__error {
  margin-top: 20px;
  color: #f15e6c;
  font-size: 14px;
}
.imp__panel {
  margin-top: 32px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  padding-top: 24px;
}
.imp__panel--bad .imp__panelhead {
  color: #f15e6c;
}
.imp__panelhead {
  font-size: 20px;
  font-weight: 800;
  display: flex;
  align-items: center;
  gap: 10px;
}
.imp__ok {
  color: var(--accent);
}
.imp__file {
  font-size: 13px;
  font-weight: 500;
}
.imp__panelnote {
  font-size: 14px;
  line-height: 1.6;
  margin-top: 12px;
  max-width: 720px;
}
.imp__bar {
  margin-top: 14px;
  height: 6px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.14);
  overflow: hidden;
  max-width: 520px;
}
.imp__bar--thin {
  height: 4px;
}
.imp__barfill {
  height: 100%;
  background: var(--accent);
  border-radius: 999px;
  transition: width 0.4s ease;
}
.imp__spinner {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.25);
  border-top-color: var(--accent);
  animation: imp-spin 0.8s linear infinite;
  flex: none;
}
@keyframes imp-spin {
  to {
    transform: rotate(360deg);
  }
}
.imp__stats {
  display: flex;
  flex-wrap: wrap;
  gap: 14px 40px;
  margin: 18px 0 4px;
}
.imp__num {
  font-size: 30px;
  font-weight: 800;
  line-height: 1.1;
}
.imp__num--green {
  color: var(--accent);
}
.imp__num--red {
  color: #f15e6c;
}
.imp__stat .muted {
  font-size: 13px;
}
.imp__actions {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 20px;
  flex-wrap: wrap;
}
/* В main.css есть только .btn-primary — вторичная нужна рядом с ней здесь. */
.btn-secondary {
  border: 1px solid rgba(255, 255, 255, 0.3);
  color: #fff;
  font-weight: 700;
  border-radius: 999px;
  padding: 13px 28px;
  font-size: 15px;
  transition: transform 0.1s ease, border-color 0.2s ease;
}
.btn-secondary:hover {
  border-color: #fff;
  transform: scale(1.04);
}
.imp__linkbtn {
  background: none;
  border: 0;
  color: var(--text-subdued);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
  text-decoration: underline;
}
.imp__linkbtn:hover {
  color: #fff;
}
.imp__adminlink {
  color: var(--accent);
  text-decoration: underline;
}
</style>
