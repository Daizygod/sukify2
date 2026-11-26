<script setup>
import { Head, Link, router } from '@inertiajs/vue3'
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import {
  collectDropped,
  collectInput,
  extOf,
  formatBytes,
  sendArchive,
  sendFolder,
  start,
} from '../../lib/bulkUpload.js'

const props = defineProps({
  uploads: Array,
  limits: Object,
  defaults: Object,
  extensions: Object,
})

const options = reactive({ ...props.defaults })
const dragging = ref(false)
const error = ref('')
const skipped = ref([])

// Текущая отправка: пока она идёт, со страницы лучше не уходить.
const job = reactive({ active: false, name: '', kind: '', total: 0, sent: 0, startedAt: 0, startedFrom: 0, retry: 0 })
let controller = null

const percent = computed(() => (job.total ? Math.min(100, Math.floor((job.sent / job.total) * 100)) : 0))
const speed = computed(() => {
  const seconds = (Date.now() - job.startedAt) / 1000
  return seconds > 2 ? (job.sent - job.startedFrom) / seconds : 0
})
const eta = computed(() => {
  if (!speed.value) return ''
  const left = Math.max(0, (job.total - job.sent) / speed.value)
  if (left < 60) return 'less than a minute left'
  const h = Math.floor(left / 3600)
  const m = Math.ceil((left % 3600) / 60)
  return h ? `about ${h} h ${m} min left` : `about ${m} min left`
})

const archiveAccept = computed(() => props.extensions.archive.map((e) => `.${e}`).join(','))
const wanted = new Set([...props.extensions.audio, ...props.extensions.image])
const interrupted = computed(() => props.uploads.filter((u) => u.status === 'uploading'))

function warnOnLeave(e) {
  e.preventDefault()
  e.returnValue = ''
}

async function run(kind, name, total, work) {
  error.value = ''
  skipped.value = []
  controller = new AbortController()
  Object.assign(job, { active: true, name, kind, total, sent: 0, startedAt: Date.now(), startedFrom: 0, retry: 0 })
  window.addEventListener('beforeunload', warnOnLeave)

  try {
    const id = await work(controller.signal)
    window.removeEventListener('beforeunload', warnOnLeave)
    router.visit(`/admin/uploads/${id}`)
  } catch (e) {
    // Остальные файлы папки летят параллельно — останавливаем и их.
    controller?.abort()
    if (e.name !== 'AbortError') error.value = e.message || String(e)
    job.active = false
    router.reload({ only: ['uploads'] })
  } finally {
    window.removeEventListener('beforeunload', warnOnLeave)
  }
}

function progress(sent, first = false) {
  if (first) {
    job.startedFrom = sent
    job.startedAt = Date.now()
  }
  job.sent = sent
  job.retry = 0
}

function uploadArchive(file) {
  run('archive', file.name, file.size, async (signal) => {
    const state = await start({ name: file.name, size: file.size, kind: 'archive', options })
    progress(state.received_bytes, true)
    await sendArchive(file, state, { signal, onProgress: (n) => progress(n), onRetry: (n) => (job.retry = n) })
    return state.id
  })
}

function uploadFolder(entries) {
  const music = entries.filter((e) => wanted.has(extOf(e.file.name)) && !e.file.name.startsWith('._'))
  if (!music.some((e) => props.extensions.audio.includes(extOf(e.file.name)))) {
    error.value = 'No music in there: mp3, flac, m4a, ogg, opus, wav, ape, wma are supported.'
    return
  }
  const total = music.reduce((n, e) => n + e.file.size, 0)
  const name = music[0].path.includes('/') ? music[0].path.split('/')[0] : 'Files'

  run('folder', name, total, async (signal) => {
    const state = await start({ name, size: total, kind: 'folder', files: music.length, options })
    progress(0, true)
    await sendFolder(music, state, {
      signal,
      onProgress: (n) => progress(n),
      onRetry: (n) => (job.retry = n),
      onSkip: (path, why) => skipped.value.push(`${path}: ${why}`),
    })
    return state.id
  })
}

function route(entries) {
  if (!entries.length) return
  const archives = entries.filter((e) => props.extensions.archive.includes(extOf(e.file.name)))
  if (archives.length === 1 && entries.length === 1) return uploadArchive(archives[0].file)
  if (archives.length === entries.length) {
    error.value = 'Drop one archive at a time.'
    return
  }
  // Папка: архивы внутри неё (обложки в zip и т.п.) просто пропускаются.
  uploadFolder(entries)
}

async function onDrop(e) {
  dragging.value = false
  if (job.active) return
  route(await collectDropped(e.dataTransfer))
}

function onArchivePicked(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (file) route([{ file, path: file.name }])
}

function onFolderPicked(e) {
  const entries = collectInput(e.target.files || [])
  e.target.value = ''
  route(entries)
}

function cancel() {
  controller?.abort()
  job.active = false
  // Показать «остановилось на N% — закинь тот же файл».
  router.reload({ only: ['uploads'] })
}

onBeforeUnmount(() => window.removeEventListener('beforeunload', warnOnLeave))

const statusBadge = {
  uploading: 'bg-amber-500/20 text-amber-400',
  queued: 'bg-neutral-700 text-neutral-200',
  unpacking: 'bg-sky-500/20 text-sky-300',
  reading: 'bg-sky-500/20 text-sky-300',
  matching: 'bg-sky-500/20 text-sky-300',
  importing: 'bg-sky-500/20 text-sky-300',
  paused: 'bg-amber-500/20 text-amber-400',
  done: 'bg-accent/20 text-accent',
  failed: 'bg-red-500/20 text-red-400',
  canceled: 'bg-neutral-800 text-neutral-500',
}
const date = (iso) => (iso ? new Date(iso).toLocaleString() : '')
</script>

<template>
  <Head title="Bulk upload" />
  <h1 class="text-2xl font-extrabold">Bulk upload</h1>
  <p class="text-sm text-neutral-400 mt-1 mb-6 max-w-3xl">
    Drop an archive (ZIP, RAR, 7z, TAR) or a folder with music — any nesting and formats. Files are matched to the
    catalog by their tags: full versions replace 30-second previews, and music that isn't in the catalog yet is added
    as artists, releases and tracks.
  </p>

  <div class="mb-6 rounded-xl bg-neutral-900 border border-neutral-800 p-5 space-y-2 text-sm">
    <label class="flex items-start gap-3">
      <input v-model="options.create_missing" type="checkbox" class="mt-1 accent-[#1ed760]" />
      <span><b>Add music that isn't in the catalog</b> <span class="text-neutral-400">— artist, release and track from the tags.</span></span>
    </label>
    <label class="flex items-start gap-3">
      <input v-model="options.replace_full" type="checkbox" class="mt-1 accent-[#1ed760]" />
      <span><b>Replace tracks that already have full audio</b> <span class="text-neutral-400">— off: only previews and empty tracks get the file.</span></span>
    </label>
    <label class="flex items-start gap-3">
      <input v-model="options.check_censorship" type="checkbox" class="mt-1 accent-[#1ed760]" />
      <span><b>Check for cut words</b> <span class="text-neutral-400">— compares each file with the 30-second previews of every Deezer edition of the song.</span></span>
    </label>
  </div>

  <div
    v-if="!job.active"
    class="mb-3 rounded-xl border-2 border-dashed p-10 text-center transition"
    :class="dragging ? 'border-accent bg-accent/5' : 'border-neutral-700 bg-neutral-900/50'"
    @dragover.prevent="dragging = true"
    @dragleave.prevent="dragging = false"
    @drop.prevent="onDrop"
  >
    <div class="text-lg font-bold">Drop an archive or a folder here</div>
    <div class="text-sm text-neutral-400 mt-1">
      Server has {{ formatBytes(limits.free_bytes) }} free — an archive needs twice its size while it's being unpacked.
    </div>
    <div class="mt-5 flex justify-center gap-3">
      <label class="cursor-pointer rounded-full bg-accent text-black text-sm font-bold px-6 py-2 hover:brightness-110">
        Choose archive
        <input type="file" class="hidden" :accept="archiveAccept" @change="onArchivePicked" />
      </label>
      <label class="cursor-pointer rounded-full border border-neutral-600 text-sm font-semibold px-6 py-2 hover:border-white">
        Choose folder
        <input type="file" class="hidden" webkitdirectory multiple @change="onFolderPicked" />
      </label>
    </div>
  </div>

  <div v-else class="mb-3 rounded-xl bg-neutral-900 border border-neutral-800 p-5">
    <div class="flex items-center justify-between gap-4">
      <div class="min-w-0">
        <div class="font-bold truncate">{{ job.name }}</div>
        <div class="text-sm text-neutral-400">
          {{ formatBytes(job.sent) }} of {{ formatBytes(job.total) }}
          <template v-if="speed"> · {{ formatBytes(speed) }}/s · {{ eta }}</template>
        </div>
      </div>
      <button class="text-sm text-neutral-400 hover:text-white" @click="cancel">Pause</button>
    </div>
    <div class="mt-3 h-2 rounded-full bg-neutral-800 overflow-hidden">
      <div class="h-full bg-accent transition-all" :style="{ width: `${percent}%` }" />
    </div>
    <p v-if="job.retry" class="text-amber-400 text-xs mt-2">Connection lost — retrying (attempt {{ job.retry }})…</p>
    <p class="text-xs text-neutral-500 mt-2">
      Keep this tab open until the upload finishes. If it breaks, drop the same {{ job.kind === 'archive' ? 'archive' : 'folder' }}
      again — it continues where it stopped.
    </p>
  </div>

  <p v-if="error" class="mb-3 rounded-md bg-red-500/15 border border-red-500/40 text-red-400 px-4 py-3 text-sm">{{ error }}</p>
  <details v-if="skipped.length" class="mb-3 text-sm text-neutral-400">
    <summary class="cursor-pointer">Skipped {{ skipped.length }} files</summary>
    <div v-for="s in skipped" :key="s" class="text-xs mt-1">{{ s }}</div>
  </details>

  <div v-if="interrupted.length && !job.active" class="mb-6 rounded-md bg-amber-500/10 border border-amber-500/30 text-amber-300 px-4 py-3 text-sm">
    <div v-for="u in interrupted" :key="u.id">
      «{{ u.name }}» stopped at {{ u.progress }}% — drop the same {{ u.kind === 'archive' ? 'archive' : 'folder' }} again to continue.
    </div>
  </div>

  <h2 class="font-bold mt-8 mb-3">Recent uploads</h2>
  <div v-if="!uploads.length" class="text-sm text-neutral-500">Nothing uploaded yet.</div>
  <div v-else class="rounded-xl bg-neutral-900 border border-neutral-800 overflow-hidden">
    <table class="w-full text-sm">
      <thead class="text-neutral-400 border-b border-neutral-800">
        <tr>
          <th class="text-left px-4 py-3 font-semibold">Upload</th>
          <th class="text-left px-4 py-3 font-semibold">Status</th>
          <th class="text-left px-4 py-3 font-semibold">Files</th>
          <th class="text-left px-4 py-3 font-semibold">Started</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="u in uploads" :key="u.id" class="border-b border-neutral-800/60 last:border-0">
          <td class="px-4 py-3">
            <Link :href="`/admin/uploads/${u.id}`" class="font-semibold hover:underline">{{ u.name }}</Link>
            <div class="text-xs text-neutral-500">{{ u.kind }} · {{ formatBytes(u.size_bytes) }}</div>
          </td>
          <td class="px-4 py-3">
            <span class="px-2 py-1 rounded text-xs" :class="statusBadge[u.status]">{{ u.status }}</span>
            <div v-if="u.stage" class="text-xs text-neutral-500 mt-1">{{ u.stage }}</div>
          </td>
          <td class="px-4 py-3 text-xs text-neutral-400 space-x-3">
            <span v-if="u.counts.done" class="text-accent">{{ u.counts.done }} imported</span>
            <span v-if="u.counts.review" class="text-amber-400">{{ u.counts.review }} to review</span>
            <span v-if="u.counts.suspect" class="text-red-400">{{ u.counts.suspect }} possibly censored</span>
            <span v-if="u.counts.failed" class="text-red-400">{{ u.counts.failed }} failed</span>
            <span v-if="!u.counts.all">—</span>
          </td>
          <td class="px-4 py-3 text-neutral-400 text-xs">{{ date(u.created_at) }}<div v-if="u.user">{{ u.user }}</div></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
