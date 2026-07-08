<script setup>
import { Head, Link, useForm, router } from '@inertiajs/vue3'
import { ref, reactive, onMounted, onUnmounted, watch } from 'vue'

const props = defineProps({ release: Object, tracks: Array })

// Mirror each track's status locally so polling can update it live. Re-sync
// whenever Inertia swaps in new props (e.g. after an upload adds a row).
const live = reactive({})
watch(
  () => props.tracks,
  (tracks) => {
    tracks.forEach((t) => {
      if (live[t.id] === undefined) live[t.id] = t.processing_status
    })
  },
  { immediate: true, deep: true }
)

const form = useForm({ title: '', audio: null })
const fileInput = ref(null)

function upload() {
  form.post(`/admin/releases/${props.release.id}/tracks`, {
    preserveScroll: true,
    onSuccess: () => {
      form.reset()
      if (fileInput.value) fileInput.value.value = ''
    },
  })
}

function destroy(t) {
  if (confirm(`Delete "${t.title}"?`)) router.delete(`/admin/tracks/${t.id}`, { preserveScroll: true })
}

// Poll processing status for tracks that aren't finished yet.
let timer
async function poll() {
  const pending = props.tracks.filter((t) => ['pending', 'processing'].includes(live[t.id]))
  if (!pending.length) return
  for (const t of pending) {
    try {
      const res = await fetch(`/admin/tracks/${t.id}/status`, { headers: { Accept: 'application/json' } })
      const data = await res.json()
      live[t.id] = data.processing_status
      if (data.processing_status === 'ready') {
        t.duration_ms = data.duration_ms
        t.loudness_lufs = data.loudness_lufs
      }
      if (data.processing_status === 'failed') t.processing_error = data.processing_error
    } catch {}
  }
}
onMounted(() => (timer = setInterval(poll, 2500)))
onUnmounted(() => clearInterval(timer))

const fmt = (ms) => (ms ? `${Math.floor(ms / 60000)}:${String(Math.floor((ms % 60000) / 1000)).padStart(2, '0')}` : '—')
const badge = {
  pending: 'bg-neutral-700 text-neutral-200',
  processing: 'bg-amber-500/20 text-amber-400',
  ready: 'bg-accent/20 text-accent',
  failed: 'bg-red-500/20 text-red-400',
}
</script>

<template>
  <Head :title="release.title" />
  <div class="flex items-center justify-between mb-6">
    <div>
      <Link href="/admin/releases" class="text-neutral-500 text-sm hover:underline">← Releases</Link>
      <h1 class="text-2xl font-extrabold mt-1">{{ release.title }}</h1>
      <p class="text-neutral-400 text-sm capitalize">{{ release.type }} · {{ release.artist?.name }}</p>
    </div>
    <Link :href="`/admin/releases/${release.id}/edit`" class="rounded-full border border-neutral-700 px-5 py-2 text-sm hover:border-white">Edit release</Link>
  </div>

  <!-- Upload -->
  <form @submit.prevent="upload" class="flex items-end gap-3 mb-6 bg-neutral-900 border border-neutral-800 rounded-xl p-4">
    <div class="flex-1">
      <label class="block text-xs font-bold mb-1 text-neutral-400">Track title</label>
      <input v-model="form.title" class="w-full rounded-md bg-neutral-950 border border-neutral-700 px-3 py-2 outline-none focus:border-accent" />
    </div>
    <div class="flex-1">
      <label class="block text-xs font-bold mb-1 text-neutral-400">Audio (mp3 / flac / ape)</label>
      <input ref="fileInput" type="file" accept=".mp3,.flac,.ape,.wav,.m4a,audio/*" @input="form.audio = $event.target.files[0]" class="text-sm text-neutral-400" />
    </div>
    <button :disabled="form.processing || !form.audio || !form.title" class="rounded-full bg-accent text-black font-bold px-6 py-2 hover:brightness-110 disabled:opacity-50">Upload</button>
  </form>
  <p v-if="form.errors.audio" class="text-red-400 text-sm mb-4">{{ form.errors.audio }}</p>

  <!-- Track list -->
  <div class="rounded-xl bg-neutral-900 border border-neutral-800 overflow-hidden">
    <table class="w-full text-sm">
      <thead class="text-neutral-400 border-b border-neutral-800">
        <tr>
          <th class="text-left px-4 py-3 font-semibold w-10">#</th>
          <th class="text-left px-4 py-3 font-semibold">Title</th>
          <th class="text-left px-4 py-3 font-semibold">Status</th>
          <th class="text-right px-4 py-3 font-semibold">Duration</th>
          <th class="text-right px-4 py-3 font-semibold">LUFS</th>
          <th class="px-4 py-3"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in tracks" :key="t.id" class="border-b border-neutral-800/60 last:border-0">
          <td class="px-4 py-3 text-neutral-500">{{ t.track_number }}</td>
          <td class="px-4 py-3 font-medium">{{ t.title }}</td>
          <td class="px-4 py-3">
            <span class="px-2 py-1 rounded text-xs font-semibold capitalize" :class="badge[live[t.id]]">{{ live[t.id] }}</span>
            <span v-if="live[t.id] === 'failed'" class="text-red-400 text-xs ml-2">{{ t.processing_error }}</span>
          </td>
          <td class="px-4 py-3 text-right tabular-nums text-neutral-400">{{ fmt(t.duration_ms) }}</td>
          <td class="px-4 py-3 text-right tabular-nums text-neutral-400">{{ t.loudness_lufs ?? '—' }}</td>
          <td class="px-4 py-3 text-right">
            <button class="text-red-400 hover:underline" @click="destroy(t)">Delete</button>
          </td>
        </tr>
        <tr v-if="!tracks.length"><td colspan="6" class="px-4 py-8 text-center text-neutral-500">No tracks yet — upload one above.</td></tr>
      </tbody>
    </table>
  </div>
</template>
