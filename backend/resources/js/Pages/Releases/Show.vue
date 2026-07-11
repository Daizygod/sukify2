<script setup>
import { Head, Link, useForm, router } from '@inertiajs/vue3'
import { ref, reactive, onMounted, onUnmounted, watch } from 'vue'
import FileInput from '../../Components/FileInput.vue'
import ArtistPicker from '../../Components/ArtistPicker.vue'

const props = defineProps({ release: Object, tracks: Array, allArtists: { type: Array, default: () => [] } })

const artistNames = new Map(props.allArtists.map((a) => [a.id, a.name]))
const editingArtists = ref(null) // id трека с открытым поиском исполнителя

function artistName(id) {
  return artistNames.get(id) || id
}

function addTrackArtist(t, artist) {
  artistNames.set(artist.id, artist.name)
  editingArtists.value = null
  if (t.artist_ids.includes(artist.id)) return
  saveTrackArtists(t, [...t.artist_ids, artist.id])
}

function removeTrackArtist(t, id) {
  if (t.artist_ids.length <= 1) return
  saveTrackArtists(t, t.artist_ids.filter((x) => x !== id))
}

function saveTrackArtists(t, ids) {
  router.put(`/admin/tracks/${t.id}`, { title: t.title, artist_ids: ids }, { preserveScroll: true })
}
const uploadKey = ref(0)

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
      uploadKey.value++ // remount FileInput to clear its filename
    },
  })
}

function destroy(t) {
  if (confirm(`Delete "${t.title}"?`)) router.delete(`/admin/tracks/${t.id}`, { preserveScroll: true })
}

function toggleUnofficial(t, value) {
  router.put(`/admin/tracks/${t.id}`, { title: t.title, unofficial: value }, { preserveScroll: true })
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
    <div class="flex items-center gap-4">
      <div class="w-20 h-20 rounded-lg overflow-hidden bg-neutral-800 grid place-items-center shrink-0">
        <img v-if="release.cover_url" :src="release.cover_url" class="w-full h-full object-cover" />
        <span v-else class="text-[10px] text-neutral-500 text-center px-1">no cover</span>
      </div>
      <div>
        <Link href="/admin/releases" class="text-neutral-500 text-sm hover:underline">← Releases</Link>
        <h1 class="text-2xl font-extrabold mt-1">{{ release.title }}</h1>
        <p class="text-neutral-400 text-sm capitalize">
          {{ release.type }} · {{ release.artist?.name }}
          <span v-if="release.cover_status !== 'ready'" class="ml-2 text-amber-400">cover: {{ release.cover_status }}</span>
        </p>
      </div>
    </div>
    <Link :href="`/admin/releases/${release.id}/edit`" class="rounded-full border border-neutral-700 px-5 py-2 text-sm hover:border-white">Edit release</Link>
  </div>

  <!-- Upload -->
  <form @submit.prevent="upload" class="mb-6 bg-neutral-900 border border-neutral-800 rounded-xl p-4">
    <div class="text-sm font-bold mb-3">Add a track</div>
    <div class="flex flex-col md:flex-row md:items-end gap-4">
      <div class="flex-1">
        <label class="block text-xs font-bold mb-1 text-neutral-400">Track title</label>
        <input v-model="form.title" placeholder="Track title" class="w-full rounded-md bg-neutral-950 border border-neutral-700 px-3 py-2 outline-none focus:border-accent" />
      </div>
      <div class="flex-1">
        <label class="block text-xs font-bold mb-2 text-neutral-400">Audio file</label>
        <FileInput
          :key="uploadKey"
          v-model="form.audio"
          accept=".mp3,.flac,.ape,.wav,.m4a,audio/*"
          label="Choose audio"
          hint="mp3 / flac / ape — transcoded automatically."
        />
      </div>
      <button :disabled="form.processing || !form.audio || !form.title" class="rounded-full bg-accent text-black font-bold px-6 py-2 hover:brightness-110 disabled:opacity-50 whitespace-nowrap">
        {{ form.processing ? 'Uploading…' : 'Upload' }}
      </button>
    </div>
    <p v-if="form.errors.audio" class="text-red-400 text-sm mt-2">{{ form.errors.audio }}</p>
  </form>

  <!-- Track list -->
  <div class="rounded-xl bg-neutral-900 border border-neutral-800 overflow-hidden">
    <table class="w-full text-sm">
      <thead class="text-neutral-400 border-b border-neutral-800">
        <tr>
          <th class="text-left px-4 py-3 font-semibold w-10">#</th>
          <th class="text-left px-4 py-3 font-semibold">Title</th>
          <th class="text-left px-4 py-3 font-semibold">Artists</th>
          <th class="text-left px-4 py-3 font-semibold">Status</th>
          <th class="text-right px-4 py-3 font-semibold">Duration</th>
          <th class="text-right px-4 py-3 font-semibold">LUFS</th>
          <th class="text-center px-4 py-3 font-semibold" title="Трека нет на официальных площадках">Exclusive</th>
          <th class="px-4 py-3"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in tracks" :key="t.id" class="border-b border-neutral-800/60 last:border-0">
          <td class="px-4 py-3 text-neutral-500">{{ t.track_number }}</td>
          <td class="px-4 py-3 font-medium">{{ t.title }}</td>
          <td class="px-4 py-3">
            <div class="flex flex-wrap items-center gap-1">
              <span
                v-for="(id, i) in t.artist_ids"
                :key="id"
                class="inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs"
                :class="i === 0 ? 'bg-neutral-700 font-semibold' : 'bg-neutral-800 text-neutral-300'"
              >
                {{ artistName(id) }}
                <button v-if="t.artist_ids.length > 1" class="opacity-60 hover:opacity-100" @click="removeTrackArtist(t, id)">✕</button>
              </span>
              <button
                class="rounded-full w-5 h-5 grid place-items-center bg-neutral-800 text-neutral-400 hover:text-white text-xs"
                title="Добавить исполнителя (feat)"
                @click="editingArtists = editingArtists === t.id ? null : t.id"
              >+</button>
            </div>
            <div v-if="editingArtists === t.id" class="mt-2 w-56">
              <ArtistPicker :exclude="t.artist_ids" placeholder="Найти исполнителя…" @pick="(a) => addTrackArtist(t, a)" />
            </div>
          </td>
          <td class="px-4 py-3">
            <span class="px-2 py-1 rounded text-xs font-semibold capitalize" :class="badge[live[t.id]]">{{ live[t.id] }}</span>
            <span v-if="live[t.id] === 'failed'" class="text-red-400 text-xs ml-2">{{ t.processing_error }}</span>
          </td>
          <td class="px-4 py-3 text-right tabular-nums text-neutral-400">{{ fmt(t.duration_ms) }}</td>
          <td class="px-4 py-3 text-right tabular-nums text-neutral-400">{{ t.loudness_lufs ?? '—' }}</td>
          <td class="px-4 py-3 text-center">
            <input
              type="checkbox"
              :checked="t.unofficial"
              class="accent-green-500 cursor-pointer"
              @change="toggleUnofficial(t, $event.target.checked)"
            />
          </td>
          <td class="px-4 py-3 text-right">
            <button class="text-red-400 hover:underline" @click="destroy(t)">Delete</button>
          </td>
        </tr>
        <tr v-if="!tracks.length"><td colspan="8" class="px-4 py-8 text-center text-neutral-500">No tracks yet — upload one above.</td></tr>
      </tbody>
    </table>
  </div>
</template>
