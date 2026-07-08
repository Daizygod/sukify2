<script setup>
import { Head, useForm, Link } from '@inertiajs/vue3'
import FileInput from '../../Components/FileInput.vue'

const props = defineProps({ release: Object, artists: Array })

const form = useForm({
  artist_id: props.release?.artist_id || props.artists[0]?.id || '',
  title: props.release?.title || '',
  type: props.release?.type || 'album',
  release_date: props.release?.release_date || '',
  cover: null,
})

function submit() {
  if (props.release) {
    form.transform((d) => ({ ...d, _method: 'put' })).post(`/admin/releases/${props.release.id}`)
  } else {
    form.post('/admin/releases')
  }
}
</script>

<template>
  <Head :title="release ? 'Edit release' : 'New release'" />
  <div class="max-w-xl">
    <h1 class="text-2xl font-extrabold mb-6">{{ release ? 'Edit release' : 'New release' }}</h1>
    <form @submit.prevent="submit" class="space-y-5">
      <div>
        <label class="block text-xs font-bold mb-1 text-neutral-400">Artist</label>
        <select v-model="form.artist_id" class="w-full rounded-md bg-neutral-900 border border-neutral-700 px-3 py-2 outline-none focus:border-accent">
          <option v-for="a in artists" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select>
      </div>
      <div>
        <label class="block text-xs font-bold mb-1 text-neutral-400">Title</label>
        <input v-model="form.title" class="w-full rounded-md bg-neutral-900 border border-neutral-700 px-3 py-2 outline-none focus:border-accent" />
        <p v-if="form.errors.title" class="text-red-400 text-sm mt-1">{{ form.errors.title }}</p>
      </div>
      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="block text-xs font-bold mb-1 text-neutral-400">Type</label>
          <select v-model="form.type" class="w-full rounded-md bg-neutral-900 border border-neutral-700 px-3 py-2 outline-none focus:border-accent">
            <option value="album">Album</option>
            <option value="single">Single</option>
            <option value="compilation">Compilation</option>
          </select>
        </div>
        <div>
          <label class="block text-xs font-bold mb-1 text-neutral-400">Release date</label>
          <input v-model="form.release_date" type="date" class="w-full rounded-md bg-neutral-900 border border-neutral-700 px-3 py-2 outline-none focus:border-accent" />
        </div>
      </div>
      <div>
        <label class="block text-xs font-bold mb-2 text-neutral-400">Cover</label>
        <FileInput
          v-model="form.cover"
          accept="image/*"
          label="Choose cover"
          preview
          :current-url="release?.cover_url"
          hint="Square image → WebP renditions + colours are generated automatically."
        />
        <p v-if="form.errors.cover" class="text-red-400 text-sm mt-1">{{ form.errors.cover }}</p>
      </div>
      <div class="flex gap-3 pt-2">
        <button :disabled="form.processing" class="rounded-full bg-accent text-black font-bold px-6 py-2 hover:brightness-110 disabled:opacity-60">Save</button>
        <Link href="/admin/releases" class="rounded-full border border-neutral-700 px-6 py-2 hover:border-white">Cancel</Link>
      </div>
    </form>
  </div>
</template>
