<script setup>
import { Head, Link, router } from '@inertiajs/vue3'
import { ref, watch } from 'vue'

const props = defineProps({ artists: Object, filters: Object })
const search = ref(props.filters.search || '')

let t
watch(search, (v) => {
  clearTimeout(t)
  t = setTimeout(() => router.get('/admin/artists', { search: v }, { preserveState: true, replace: true }), 300)
})

function destroy(artist) {
  if (confirm(`Delete "${artist.name}"? This removes their releases and tracks.`)) {
    router.delete(`/admin/artists/${artist.id}`)
  }
}
</script>

<template>
  <Head title="Artists" />
  <div class="flex items-center justify-between mb-6">
    <h1 class="text-2xl font-extrabold">Artists</h1>
    <Link href="/admin/artists/create" class="rounded-full bg-accent text-black font-bold px-5 py-2 text-sm hover:brightness-110">New artist</Link>
  </div>

  <input v-model="search" placeholder="Search artists…" class="mb-4 w-72 rounded-md bg-neutral-900 border border-neutral-700 px-3 py-2 text-sm outline-none focus:border-accent" />

  <div class="rounded-xl bg-neutral-900 border border-neutral-800 overflow-hidden">
    <table class="w-full text-sm">
      <thead class="text-neutral-400 border-b border-neutral-800">
        <tr>
          <th class="text-left px-4 py-3 font-semibold">Name</th>
          <th class="text-left px-4 py-3 font-semibold">Releases</th>
          <th class="text-left px-4 py-3 font-semibold">Monthly listeners</th>
          <th class="px-4 py-3"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="a in artists.data" :key="a.id" class="border-b border-neutral-800/60 last:border-0">
          <td class="px-4 py-3 font-medium">{{ a.name }}</td>
          <td class="px-4 py-3 text-neutral-400">{{ a.releases_count }}</td>
          <td class="px-4 py-3 text-neutral-400 tabular-nums">{{ a.monthly_listeners.toLocaleString() }}</td>
          <td class="px-4 py-3 text-right whitespace-nowrap">
            <Link :href="`/admin/artists/${a.id}/edit`" class="text-accent hover:underline mr-4">Edit</Link>
            <button class="text-red-400 hover:underline" @click="destroy(a)">Delete</button>
          </td>
        </tr>
        <tr v-if="!artists.data.length"><td colspan="4" class="px-4 py-8 text-center text-neutral-500">No artists yet.</td></tr>
      </tbody>
    </table>
  </div>
</template>
