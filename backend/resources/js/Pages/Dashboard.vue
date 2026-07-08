<script setup>
import { Head } from '@inertiajs/vue3'

defineProps({ stats: Object, topTracks: Array })

const cards = (s) => [
  { label: 'Artists', value: s.artists },
  { label: 'Releases', value: s.releases },
  { label: 'Tracks', value: s.tracks },
  { label: 'Users', value: s.users },
  { label: 'Playlists', value: s.playlists },
  { label: 'Processing', value: s.processing, warn: s.processing > 0 },
]
</script>

<template>
  <Head title="Dashboard" />
  <h1 class="text-2xl font-extrabold mb-6">Dashboard</h1>

  <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-4 mb-8">
    <div v-for="c in cards(stats)" :key="c.label" class="rounded-xl bg-neutral-900 border border-neutral-800 p-4">
      <div class="text-3xl font-extrabold" :class="c.warn ? 'text-amber-400' : ''">{{ c.value }}</div>
      <div class="text-sm text-neutral-400 mt-1">{{ c.label }}</div>
    </div>
  </div>

  <h2 class="text-lg font-bold mb-3">Top tracks by plays</h2>
  <div class="rounded-xl bg-neutral-900 border border-neutral-800 overflow-hidden">
    <table class="w-full text-sm">
      <thead class="text-neutral-400 border-b border-neutral-800">
        <tr>
          <th class="text-left px-4 py-3 font-semibold">Title</th>
          <th class="text-left px-4 py-3 font-semibold">Release</th>
          <th class="text-right px-4 py-3 font-semibold">Plays</th>
          <th class="text-right px-4 py-3 font-semibold">Likes</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="t in topTracks" :key="t.id" class="border-b border-neutral-800/60 last:border-0">
          <td class="px-4 py-3">{{ t.title }}</td>
          <td class="px-4 py-3 text-neutral-400">{{ t.release }}</td>
          <td class="px-4 py-3 text-right tabular-nums">{{ t.plays_count.toLocaleString() }}</td>
          <td class="px-4 py-3 text-right tabular-nums">{{ t.likes_count.toLocaleString() }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
