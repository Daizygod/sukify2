<script setup>
import { ref } from 'vue'
import api from '@/lib/api'
import Icon from '@/components/Icon.vue'
import { useAuthStore } from '@/stores/auth'
import { useLibraryStore } from '@/stores/library'
import { useToastStore } from '@/stores/toasts'

const auth = useAuthStore()
const library = useLibraryStore()
const toasts = useToastStore()

const dragOver = ref(false)
const parsing = ref(false)
const importing = ref(false)
const parsedCount = ref(0)
const result = ref(null)
const error = ref('')

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

/** Exportify CSV → нормализованные позиции. */
function fromExportify(text) {
  const rows = parseCsv(text)
  if (!rows.length) return []
  const head = rows[0].map((h) => h.toLowerCase())
  const col = (names) => head.findIndex((h) => names.some((n) => h.includes(n)))
  const iTitle = col(['track name', 'название трека'])
  const iArtists = col(['artist name', 'исполнител'])
  const iDur = col(['duration'])
  if (iTitle === -1) return []
  return rows.slice(1).map((r) => ({
    title: r[iTitle] || '',
    artists: (r[iArtists] || '').split(/[,;]/).map((s) => s.trim()).filter(Boolean),
    duration_ms: iDur !== -1 ? parseInt(r[iDur]) || null : null,
  })).filter((x) => x.title)
}

/** Официальный экспорт Spotify (YourLibrary.json) → позиции. */
function fromSpotifyJson(obj) {
  const tracks = obj?.tracks || obj?.likedSongs || (Array.isArray(obj) ? obj : [])
  return tracks
    .map((t) => ({
      title: t.track || t.trackName || t.title || '',
      artists: [t.artist || t.artistName].filter(Boolean),
      duration_ms: null,
    }))
    .filter((x) => x.title)
}

async function handleFile(file) {
  error.value = ''
  result.value = null
  parsing.value = true
  try {
    const text = await file.text()
    let items = []
    if (file.name.endsWith('.json') || text.trim().startsWith('{') || text.trim().startsWith('[')) {
      items = fromSpotifyJson(JSON.parse(text))
    } else {
      items = fromExportify(text)
    }
    if (!items.length) {
      error.value = 'Не смог разобрать файл: не нашёл в нём треков. Проверь, что это CSV из Exportify или YourLibrary.json из экспорта Spotify.'
      return
    }
    parsedCount.value = items.length
    importing.value = true
    const { data } = await api.post('/import/liked', { items: items.slice(0, 5000) })
    result.value = data
    library.load().catch(() => {})
    toasts.show(`Импорт готов: добавлено ${data.added}`)
  } catch (e) {
    error.value = 'Что-то пошло не так при разборе файла. Убедись, что это CSV/JSON из Spotify.'
  } finally {
    parsing.value = false
    importing.value = false
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
</script>

<template>
  <div class="content-pad imp">
    <h1 class="imp__title">Импорт любимых треков из Spotify</h1>
    <p class="muted imp__lead">
      Перенеси свои (или друга) «Любимые треки» из Spotify в Sukify за пару минут.
      Что найдётся в каталоге — сразу попадёт в «Любимые треки»; чего нет — покажем списком.
    </p>

    <div class="imp__cols">
      <section class="imp__how">
        <h2>Способ 1: Exportify <span class="imp__badge">быстрее</span></h2>
        <ol>
          <li>Открой <a href="https://exportify.net" target="_blank" rel="noopener">exportify.net</a> и нажми <b>Get Started</b>.</li>
          <li>Войди в аккаунт Spotify и разреши доступ (только чтение плейлистов).</li>
          <li>Вверху списка напротив <b>Liked Songs</b> нажми <b>Export</b> — скачается CSV-файл.</li>
          <li>Закинь этот файл сюда →</li>
        </ol>

        <h2>Способ 2: официальный экспорт Spotify</h2>
        <ol>
          <li>Зайди на <a href="https://www.spotify.com/account/privacy/" target="_blank" rel="noopener">spotify.com/account/privacy</a>.</li>
          <li>Внизу страницы отметь <b>«Данные аккаунта»</b> и нажми «Запросить данные».</li>
          <li>Подтверди запрос по письму. Архив придёт на почту в течение ~5 дней.</li>
          <li>Из архива нужен файл <b>YourLibrary.json</b> — закинь его сюда →</li>
        </ol>

        <p class="muted imp__note">
          Треков нет в каталоге? Загрузите их через админку — и импорт можно повторить:
          уже добавленные лайки не задублируются. Треки, которых нет на официальных
          площадках (эксклюзивы), помечаются в Sukify специальным бейджем.
        </p>
      </section>

      <section
        class="imp__drop"
        :class="{ over: dragOver, busy: parsing || importing }"
        @dragover.prevent="dragOver = true"
        @dragleave="dragOver = false"
        @drop.prevent="onDrop"
      >
        <template v-if="parsing || importing">
          <p class="imp__dropline">{{ importing ? `Импортирую ${parsedCount} трек(ов)…` : 'Читаю файл…' }}</p>
        </template>
        <template v-else>
          <Icon name="install" :size="40" class="imp__dropicon" />
          <p class="imp__dropline">Перетащи сюда CSV или JSON</p>
          <label class="btn-primary imp__pick">
            Выбрать файл
            <input type="file" accept=".csv,.json,text/csv,application/json" hidden @change="onPick" />
          </label>
        </template>
      </section>
    </div>

    <p v-if="error" class="imp__error">{{ error }}</p>

    <section v-if="result" class="imp__result">
      <h2>Готово!</h2>
      <div class="imp__stats">
        <div class="imp__stat">
          <div class="imp__num imp__num--green">{{ result.added }}</div>
          <div class="muted">добавлено в любимые</div>
        </div>
        <div class="imp__stat">
          <div class="imp__num">{{ result.already }}</div>
          <div class="muted">уже были</div>
        </div>
        <div class="imp__stat">
          <div class="imp__num" :class="{ 'imp__num--red': result.missing.length }">{{ result.missing.length }}</div>
          <div class="muted">нет в каталоге</div>
        </div>
      </div>

      <template v-if="result.missing.length">
        <h3 class="imp__misshead">Не нашлись в каталоге Sukify</h3>
        <p class="muted imp__missnote">
          Эти треки нужно сначала загрузить
          <template v-if="auth.isAdmin"> — <RouterLink to="/admin" class="imp__adminlink">открыть админку</RouterLink></template>
          <template v-else> (попроси админа)</template>. Потом просто повтори импорт этим же файлом.
        </p>
        <ul class="imp__missing">
          <li v-for="(m, i) in result.missing.slice(0, 100)" :key="i">
            <span class="imp__missartist">{{ m.artists.join(', ') || '—' }}</span> — {{ m.title }}
          </li>
        </ul>
        <p v-if="result.missing.length > 100" class="muted">…и ещё {{ result.missing.length - 100 }}.</p>
      </template>
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
  max-width: 640px;
  line-height: 1.5;
}
.imp__cols {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 32px;
  align-items: start;
}
@media (max-width: 1000px) {
  .imp__cols {
    grid-template-columns: 1fr;
  }
}
.imp__how h2 {
  font-size: 17px;
  font-weight: 700;
  margin: 20px 0 10px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.imp__how h2:first-child {
  margin-top: 0;
}
.imp__badge {
  background: var(--accent);
  color: #000;
  font-size: 10px;
  font-weight: 800;
  text-transform: uppercase;
  border-radius: 999px;
  padding: 2px 8px;
}
.imp__how ol {
  margin: 0 0 8px 20px;
  color: var(--text-subdued);
  font-size: 14px;
  line-height: 1.9;
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
  max-width: 560px;
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
.imp__error {
  margin-top: 20px;
  color: #f15e6c;
  font-size: 14px;
}
.imp__result {
  margin-top: 32px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  padding-top: 24px;
}
.imp__result h2 {
  font-size: 20px;
  font-weight: 800;
}
.imp__stats {
  display: flex;
  gap: 32px;
  margin: 16px 0 8px;
}
.imp__num {
  font-size: 32px;
  font-weight: 800;
}
.imp__num--green {
  color: var(--accent);
}
.imp__num--red {
  color: #f15e6c;
}
.imp__misshead {
  font-size: 16px;
  font-weight: 700;
  margin-top: 20px;
}
.imp__missnote {
  font-size: 14px;
  margin: 6px 0 12px;
}
.imp__adminlink {
  color: var(--accent);
  text-decoration: underline;
}
.imp__missing {
  list-style: none;
  font-size: 14px;
  color: var(--text-subdued);
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 320px;
  overflow-y: auto;
}
.imp__missartist {
  color: #fff;
}
</style>
