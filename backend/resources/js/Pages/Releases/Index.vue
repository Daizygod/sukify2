<script setup>
import { Head, Link, router } from '@inertiajs/vue3'
import { ref, watch } from 'vue'

const props = defineProps({ releases: Object, filters: Object })
const search = ref(props.filters.search || '')

let t
watch(search, (v) => {
  clearTimeout(t)
  t = setTimeout(() => router.get('/admin/releases', { search: v }, { preserveState: true, replace: true }), 300)
})

function destroy(r) {
  if (confirm(`Delete "${r.title}" and its tracks?`)) router.delete(`/admin/releases/${r.id}`)
}
</script>

<template>
  <Head title="Releases" />
  <div class="flex items-center justify-between mb-6">
    <h1 class="text-2xl font-extrabold">Releases</h1>
    <Link href="/admin/releases/create" class="rounded-full bg-accent text-black font-bold px-5 py-2 text-sm hover:brightness-110">New release</Link>
  </div>

  <input v-model="search" placeholder="Search releases…" class="mb-4 w-72 rounded-md bg-neutral-900 border border-neutral-700 px-3 py-2 text-sm outline-none focus:border-accent" />

  <div class="rounded-xl bg-neutral-900 border border-neutral-800 overflow-hidden">
    <table class="w-full text-sm">
      <thead class="text-neutral-400 border-b border-neutral-800">
        <tr>
          <th class="text-left px-4 py-3 font-semibold">Title</th>
          <th class="text-left px-4 py-3 font-semibold">Artist</th>
          <th class="text-left px-4 py-3 font-semibold">Type</th>
          <th class="text-left px-4 py-3 font-semibold">Tracks</th>
          <th class="px-4 py-3"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="r in releases.data" :key="r.id" class="border-b border-neutral-800/60 last:border-0">
          <td class="px-4 py-3 font-medium">
            <Link :href="`/admin/releases/${r.id}`" class="hover:underline">{{ r.title }}</Link>
          </td>
          <td class="px-4 py-3 text-neutral-400">{{ r.artist?.name }}</td>
          <td class="px-4 py-3 text-neutral-400 capitalize">{{ r.type }}</td>
          <td class="px-4 py-3 text-neutral-400">{{ r.tracks_count }}</td>
          <td class="px-4 py-3 text-right whitespace-nowrap">
            <Link :href="`/admin/releases/${r.id}`" class="text-accent hover:underline mr-4">Manage</Link>
            <button class="text-red-400 hover:underline" @click="destroy(r)">Delete</button>
          </td>
        </tr>
        <tr v-if="!releases.data.length"><td colspan="5" class="px-4 py-8 text-center text-neutral-500">No releases yet.</td></tr>
      </tbody>
    </table>
  </div>
</template>
