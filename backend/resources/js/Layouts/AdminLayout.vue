<script setup>
import { Link, usePage, router } from '@inertiajs/vue3'
import { computed } from 'vue'

const page = usePage()
const user = computed(() => page.props.auth?.user)
const flash = computed(() => page.props.flash || {})

const nav = [
  { label: 'Dashboard', href: '/admin', icon: '▦' },
  { label: 'Artists', href: '/admin/artists', icon: '🎤' },
  { label: 'Releases', href: '/admin/releases', icon: '💿' },
  { label: 'Users', href: '/admin/users', icon: '👥' },
  { label: 'My profile', href: '/admin/profile', icon: '🔐' },
]

function isActive(href) {
  if (href === '/admin') return page.url === '/admin'
  return page.url.startsWith(href)
}
function logout() {
  router.post('/admin/logout')
}
</script>

<template>
  <div class="flex min-h-screen">
    <aside class="w-60 shrink-0 bg-black border-r border-neutral-800 flex flex-col">
      <div class="px-5 py-5 text-lg font-extrabold">🎧 Sukify <span class="text-accent">Admin</span></div>
      <nav class="flex-1 px-3 space-y-1">
        <Link
          v-for="item in nav"
          :key="item.href"
          :href="item.href"
          class="flex items-center gap-3 px-3 py-2 rounded-md text-sm font-semibold transition"
          :class="isActive(item.href) ? 'bg-neutral-800 text-white' : 'text-neutral-400 hover:text-white'"
        >
          <span>{{ item.icon }}</span> {{ item.label }}
        </Link>
      </nav>
      <div class="p-4 border-t border-neutral-800 text-sm">
        <div class="text-neutral-400 mb-2 truncate">{{ user?.name }}</div>
        <button class="text-neutral-400 hover:text-white" @click="logout">Log out</button>
      </div>
    </aside>

    <main class="flex-1 min-w-0 bg-neutral-950">
      <div
        v-if="flash.success"
        class="m-6 mb-0 rounded-md bg-accent/15 border border-accent/40 text-accent px-4 py-3 text-sm"
      >{{ flash.success }}</div>
      <div
        v-if="flash.error"
        class="m-6 mb-0 rounded-md bg-red-500/15 border border-red-500/40 text-red-400 px-4 py-3 text-sm"
      >{{ flash.error }}</div>

      <div class="p-6">
        <slot />
      </div>
    </main>
  </div>
</template>
