<script setup>
import { Head, router } from '@inertiajs/vue3'
import { ref, watch } from 'vue'

const props = defineProps({ users: Object, filters: Object })
const search = ref(props.filters.search || '')

let t
watch(search, (v) => {
  clearTimeout(t)
  t = setTimeout(() => router.get('/admin/users', { search: v }, { preserveState: true, replace: true }), 300)
})

function toggleBan(u) {
  const verb = u.is_banned ? 'Unban' : 'Ban'
  if (confirm(`${verb} ${u.name}?`)) {
    router.put(`/admin/users/${u.id}/ban`, {}, { preserveScroll: true })
  }
}
</script>

<template>
  <Head title="Users" />
  <h1 class="text-2xl font-extrabold mb-6">Users</h1>

  <input v-model="search" placeholder="Search users…" class="mb-4 w-72 rounded-md bg-neutral-900 border border-neutral-700 px-3 py-2 text-sm outline-none focus:border-accent" />

  <div class="rounded-xl bg-neutral-900 border border-neutral-800 overflow-hidden">
    <table class="w-full text-sm">
      <thead class="text-neutral-400 border-b border-neutral-800">
        <tr>
          <th class="text-left px-4 py-3 font-semibold">Name</th>
          <th class="text-left px-4 py-3 font-semibold">Email</th>
          <th class="text-left px-4 py-3 font-semibold">Playlists</th>
          <th class="text-left px-4 py-3 font-semibold">Joined</th>
          <th class="text-left px-4 py-3 font-semibold">Status</th>
          <th class="px-4 py-3"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="u in users.data" :key="u.id" class="border-b border-neutral-800/60 last:border-0">
          <td class="px-4 py-3 font-medium">
            {{ u.name }}
            <span v-if="u.is_admin" class="ml-2 px-2 py-0.5 rounded bg-accent/20 text-accent text-xs">admin</span>
          </td>
          <td class="px-4 py-3 text-neutral-400">{{ u.email }}</td>
          <td class="px-4 py-3 text-neutral-400">{{ u.playlists_count }}</td>
          <td class="px-4 py-3 text-neutral-400">{{ u.created_at }}</td>
          <td class="px-4 py-3">
            <span v-if="u.is_banned" class="px-2 py-1 rounded bg-red-500/20 text-red-400 text-xs">banned</span>
            <span v-else class="px-2 py-1 rounded bg-neutral-700 text-neutral-200 text-xs">active</span>
          </td>
          <td class="px-4 py-3 text-right">
            <button v-if="!u.is_admin" class="hover:underline" :class="u.is_banned ? 'text-accent' : 'text-red-400'" @click="toggleBan(u)">
              {{ u.is_banned ? 'Unban' : 'Ban' }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
