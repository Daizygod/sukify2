<script setup>
import { Head, Link, router } from '@inertiajs/vue3'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import TrackPicker from '../../Components/TrackPicker.vue'
import { formatBytes, formatDuration } from '../../lib/bulkUpload.js'

const props = defineProps({ upload: Object, counts: Object, items: Object, filters: Object })

const tabs = computed(() => [
  { key: 'all', label: 'All', n: props.counts.all },
  { key: 'review', label: 'Needs review', n: props.counts.review, tone: 'text-amber-400' },
  { key: 'importing', label: 'Importing', n: props.counts.importing },
  { key: 'done', label: 'Imported', n: props.counts.done, tone: 'text-accent' },
  { key: 'suspect', label: 'Possibly censored', n: props.counts.suspect, tone: 'text-red-400' },
  { key: 'skipped', label: 'Skipped', n: props.counts.skipped },
  { key: 'duplicate', label: 'Duplicates', n: props.counts.duplicate },
  { key: 'failed', label: 'Failed', n: props.counts.failed, tone: 'text-red-400' },
])

const search = ref(props.filters.q || '')
let searchTimer
watch(search, (v) => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => go({ q: v || undefined, page: undefined }), 300)
})

function go(params) {
  router.get(`/admin/uploads/${props.upload.id}`, { tab: props.filters.tab, q: props.filters.q || undefined, ...params }, {
    preserveState: true,
    preserveScroll: true,
    replace: true,
  })
}

// Пока очередь работает — подтягиваем свежие статусы.
const busy = computed(() => props.upload.active || props.counts.importing > 0 || props.counts.pending > 0)
let poll
onMounted(() => {
  poll = setInterval(() => {
    if (busy.value && !document.hidden) router.reload({ only: ['upload', 'counts', 'items'], preserveScroll: true })
  }, 3000)
})
onBeforeUnmount(() => {
  clearInterval(poll)
  clearTimeout(searchTimer)
})

function resolve(item, action, trackId = null) {
  router.post(`/admin/uploads/${props.upload.id}/items/${item.id}`, { action, track_id: trackId }, { preserveScroll: true })
}

function resolveAll(action, reason = null) {
  const what = { create: 'Add all of them as new tracks?', best: 'Put each file into its best guess?', skip: 'Skip all of them?' }[action]
  if (confirm(what)) router.post(`/admin/uploads/${props.upload.id}/resolve-all`, { action, reason }, { preserveScroll: true })
}

function retryUpload() {
  router.post(`/admin/uploads/${props.upload.id}/retry`, {}, { preserveScroll: true })
}

function destroy() {
  if (confirm('Remove this upload and its files from the server? Tracks that were already imported stay.')) {
    router.delete(`/admin/uploads/${props.upload.id}`)
  }
}

const pickingFor = ref(null)

function fileName(path) {
  return path.split('/').pop()
}
function folder(path) {
  const parts = path.split('/')
  parts.pop()
  return parts.join(' / ')
}
function format(t) {
  if (!t?.codec) return ''
  const codec = t.codec.startsWith('pcm_') ? 'WAV' : t.codec === 'vorbis' ? 'OGG' : t.codec.toUpperCase()
  const quality = t.lossless
    ? [t.bits ? `${t.bits}-bit` : null, t.sample_rate ? `${(t.sample_rate / 1000).toFixed(1)} kHz` : null].filter(Boolean).join(' · ')
    : t.bit_rate ? `${Math.round(t.bit_rate / 1000)} kbps` : ''
  return [codec, quality, formatDuration(t.duration_ms)].filter(Boolean).join(' · ')
}
function at(ms) {
  return formatDuration(ms)
}

const reasons = {
  matched: 'Matched by tags',
  created: 'Added as a new track',
  manual: 'Chosen by hand',
  ambiguous: 'Not sure which track this is',
  no_match: 'Not found in the catalog',
  has_full_audio: 'Already has full audio',
  better_copy: 'A better copy of the same song was used',
  unreadable: 'Not an audio file or damaged',
  expired: 'File was removed from the server',
  canceled: 'Upload was canceled',
}

const badge = {
  pending: 'bg-neutral-700 text-neutral-200',
  review: 'bg-amber-500/20 text-amber-400',
  queued: 'bg-neutral-700 text-neutral-200',
  processing: 'bg-sky-500/20 text-sky-300',
  done: 'bg-accent/20 text-accent',
  skipped: 'bg-neutral-800 text-neutral-400',
  duplicate: 'bg-neutral-800 text-neutral-400',
  failed: 'bg-red-500/20 text-red-400',
}

function censorText(item) {
  const info = item.censor_info || {}
  if (item.censor === 'suspect' || item.censor === 'differs') {
    const parts = (info.editions || [])
      .filter((e) => e.aligned && (item.censor === 'suspect' ? e.missing?.length : e.differs?.length))
      .map((e) => {
        const spans = (item.censor === 'suspect' ? e.missing : e.differs).map(([a, b]) => `${at(a)}–${at(b)}`).join(', ')
        return `«${e.album}»: ${spans}`
      })
    const lead = item.censor === 'suspect'
      ? 'The voice is missing where a Deezer edition has it'
      : 'Sounds different from a Deezer preview'
    return `${lead} — ${parts.join('; ')}`
  }
  if (item.censor === 'clean') {
    const fuller = (info.editions || []).filter((e) => e.extra?.length).map((e) => `«${e.album}»`)
    return fuller.length
      ? `Matches the Deezer previews; words are cut in the edition ${fuller.join(', ')} — this file is fuller.`
      : 'Matches the Deezer previews.'
  }
  return info.note || 'Not checked.'
}
const censorBadge = {
  suspect: ['Possibly censored', 'bg-red-500/20 text-red-400'],
  differs: ['Differs from preview', 'bg-amber-500/20 text-amber-400'],
  clean: ['Uncut in preview', 'bg-accent/15 text-accent'],
  unknown: ['Not checked', 'bg-neutral-800 text-neutral-400'],
}
</script>

<template>
  <Head :title="upload.name" />
  <div class="flex items-start justify-between gap-4 mb-6">
    <div class="min-w-0">
      <Link href="/admin/uploads" class="text-neutral-500 text-sm hover:underline">← Bulk upload</Link>
      <h1 class="text-2xl font-extrabold mt-1 truncate">{{ upload.name }}</h1>
      <p class="text-sm text-neutral-400">
        {{ upload.kind }} · {{ formatBytes(upload.size_bytes) }} · {{ upload.status }}
        <template v-if="upload.stage"> · {{ upload.stage }}</template>
      </p>
    </div>
    <button class="shrink-0 rounded-full border border-neutral-700 px-5 py-2 text-sm hover:border-red-400 hover:text-red-400" @click="destroy">
      Remove upload
    </button>
  </div>

  <div v-if="busy" class="mb-6 h-2 rounded-full bg-neutral-800 overflow-hidden">
    <div class="h-full bg-accent transition-all" :style="{ width: `${upload.progress}%` }" />
  </div>

  <div v-if="upload.status === 'failed'" class="mb-6 rounded-md bg-red-500/15 border border-red-500/40 text-red-400 px-4 py-3 text-sm flex items-center justify-between gap-4">
    <span>{{ upload.error }}</span>
    <button class="shrink-0 rounded-full bg-red-500/20 px-4 py-1.5 font-semibold hover:bg-red-500/30" @click="retryUpload">Retry</button>
  </div>
  <div v-if="upload.status === 'paused'" class="mb-6 rounded-md bg-amber-500/10 border border-amber-500/30 text-amber-300 px-4 py-3 text-sm flex items-center justify-between gap-4">
    <span>{{ upload.stage }}</span>
    <button class="shrink-0 rounded-full bg-amber-500/20 px-4 py-1.5 font-semibold hover:bg-amber-500/30" @click="retryUpload">Continue</button>
  </div>

  <div class="flex flex-wrap items-center gap-2 mb-4">
    <button
      v-for="t in tabs"
      :key="t.key"
      v-show="t.key === 'all' || t.n"
      class="rounded-full px-4 py-1.5 text-sm font-semibold border transition"
      :class="filters.tab === t.key ? 'bg-white text-black border-white' : 'border-neutral-700 hover:border-neutral-400'"
      @click="go({ tab: t.key, page: undefined })"
    >
      {{ t.label }} <span :class="filters.tab === t.key ? '' : t.tone">{{ t.n }}</span>
    </button>
    <input v-model="search" placeholder="Filter by file name…" class="ml-auto w-64 rounded-md bg-neutral-900 border border-neutral-700 px-3 py-1.5 text-sm outline-none focus:border-accent" />
  </div>

  <div v-if="filters.tab === 'review' && counts.review" class="mb-4 rounded-xl bg-neutral-900 border border-neutral-800 p-4 text-sm flex flex-wrap items-center gap-3">
    <span class="text-neutral-400 mr-auto">For all files waiting for a decision:</span>
    <button class="rounded-full border border-neutral-600 px-4 py-1.5 font-semibold hover:border-white" @click="resolveAll('best', 'ambiguous')">Accept best guesses</button>
    <button class="rounded-full border border-neutral-600 px-4 py-1.5 font-semibold hover:border-white" @click="resolveAll('create', 'no_match')">Add unmatched as new</button>
    <button class="rounded-full border border-neutral-600 px-4 py-1.5 font-semibold hover:border-red-400 hover:text-red-400" @click="resolveAll('skip')">Skip all</button>
  </div>

  <div class="rounded-xl bg-neutral-900 border border-neutral-800 overflow-hidden">
    <table class="w-full text-sm">
      <thead class="text-neutral-400 border-b border-neutral-800">
        <tr>
          <th class="text-left px-4 py-3 font-semibold w-[38%]">File</th>
          <th class="text-left px-4 py-3 font-semibold">Result</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items.data" :key="item.id" class="border-b border-neutral-800/60 last:border-0 align-top">
          <td class="px-4 py-3">
            <div class="text-xs text-neutral-500 truncate" :title="item.path">{{ folder(item.path) || '—' }}</div>
            <div class="font-semibold break-all">{{ fileName(item.path) }}</div>
            <div v-if="item.tags.title" class="text-xs text-neutral-300 mt-1">
              {{ item.tags.artist || 'no artist' }}<span v-if="item.tags.artist_from === 'folder'" class="text-neutral-500" title="Guessed from the folder name"> (folder?)</span>
              — {{ item.tags.title }}<span v-if="item.tags.album" class="text-neutral-500"> · {{ item.tags.album }}</span>
            </div>
            <div class="text-xs text-neutral-500">{{ format(item.tags) || formatBytes(item.size) }}</div>
          </td>

          <td class="px-4 py-3">
            <div class="flex flex-wrap items-center gap-2">
              <span class="px-2 py-0.5 rounded text-xs" :class="badge[item.status]">{{ item.status }}</span>
              <span v-if="item.reason" class="text-xs text-neutral-400">{{ reasons[item.reason] || item.reason }}</span>
              <span v-if="item.score != null && ['review', 'done', 'queued'].includes(item.status)" class="text-xs text-neutral-600">match {{ item.score }}</span>
              <span
                v-if="item.censor"
                class="px-2 py-0.5 rounded text-xs cursor-help"
                :class="censorBadge[item.censor]?.[1]"
                :title="censorText(item)"
              >{{ censorBadge[item.censor]?.[0] }}</span>
            </div>

            <div v-if="item.targets.length && item.status !== 'review'" class="mt-1 text-xs space-y-0.5">
              <div v-for="t in item.targets" :key="t.id">
                <span class="text-neutral-500">{{ ['done', 'queued', 'processing'].includes(item.status) ? '→' : 'track:' }}</span> <Link :href="`/admin/releases/${t.release_id}`" class="hover:underline text-neutral-200">{{ t.artists }} — {{ t.title }}</Link>
                <span class="text-neutral-500"> · {{ t.release }}</span>
              </div>
            </div>

            <p v-if="item.censor === 'suspect' || item.censor === 'differs'" class="mt-1 text-xs text-red-300/80">{{ censorText(item) }}</p>
            <p v-if="item.error" class="mt-1 text-xs text-red-400">{{ item.error }}</p>

            <!-- Решение по одному файлу -->
            <div v-if="item.status === 'review'" class="mt-2 space-y-1.5">
              <div v-if="item.candidates.length" class="text-xs text-neutral-500">
                {{ item.reason === 'ambiguous' ? 'Which one is it?' : 'Closest, but not sure:' }}
              </div>
              <div v-for="c in item.candidates" :key="c.id" class="flex items-center gap-2 text-xs">
                <button class="rounded-full bg-accent/20 text-accent px-3 py-0.5 font-semibold hover:bg-accent/30" @click="resolve(item, 'attach', c.id)">Use</button>
                <span class="truncate">{{ c.artists }} — {{ c.title }} <span class="text-neutral-500">· {{ c.release }} · {{ c.full ? 'full' : c.preview ? 'preview' : 'no audio' }} · {{ c.score }}</span></span>
              </div>
              <div class="flex flex-wrap items-center gap-2 pt-1">
                <button v-if="item.creatable" class="rounded-full border border-neutral-600 px-3 py-0.5 text-xs font-semibold hover:border-white" @click="resolve(item, 'create')">Add as new track</button>
                <button class="rounded-full border border-neutral-600 px-3 py-0.5 text-xs font-semibold hover:border-white" @click="pickingFor = pickingFor === item.id ? null : item.id">Find another…</button>
                <button class="text-xs text-neutral-500 hover:text-red-400" @click="resolve(item, 'skip')">Skip</button>
              </div>
              <div v-if="pickingFor === item.id" class="max-w-md">
                <TrackPicker @pick="(t) => { pickingFor = null; resolve(item, 'attach', t.id) }" />
              </div>
            </div>

            <div v-else-if="item.has_file && item.reason !== 'unreadable' && ['skipped', 'duplicate', 'failed'].includes(item.status)" class="mt-2 flex flex-wrap gap-2">
              <button v-if="item.status === 'failed'" class="rounded-full border border-neutral-600 px-3 py-0.5 text-xs font-semibold hover:border-white" @click="resolve(item, 'retry')">Retry</button>
              <button v-if="item.status === 'skipped' && item.reason === 'has_full_audio'" class="rounded-full border border-neutral-600 px-3 py-0.5 text-xs font-semibold hover:border-white" @click="resolve(item, 'replace')">Replace with this file</button>
              <button v-if="item.status === 'duplicate'" class="rounded-full border border-neutral-600 px-3 py-0.5 text-xs font-semibold hover:border-white" @click="resolve(item, 'replace')">Use this copy instead</button>
              <button class="rounded-full border border-neutral-600 px-3 py-0.5 text-xs font-semibold hover:border-white" @click="pickingFor = pickingFor === item.id ? null : item.id">Put into another track…</button>
              <div v-if="pickingFor === item.id" class="w-full max-w-md">
                <TrackPicker @pick="(t) => { pickingFor = null; resolve(item, 'attach', t.id) }" />
              </div>
            </div>
          </td>
        </tr>
        <tr v-if="!items.data.length">
          <td colspan="2" class="px-4 py-8 text-center text-neutral-500">{{ busy ? 'Working on it…' : 'Nothing here.' }}</td>
        </tr>
      </tbody>
    </table>
  </div>

  <div v-if="items.last_page > 1" class="mt-4 flex flex-wrap gap-1">
    <template v-for="link in items.links" :key="link.label">
      <Link
        v-if="link.url"
        :href="link.url"
        preserve-scroll
        class="px-3 py-1 rounded text-sm"
        :class="link.active ? 'bg-white text-black' : 'text-neutral-400 hover:text-white'"
        v-html="link.label"
      />
    </template>
  </div>
</template>
