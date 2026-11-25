<script setup>
import { Head, router, useForm, usePage } from '@inertiajs/vue3'
import { computed, ref, watch } from 'vue'

const props = defineProps({ users: Object, filters: Object, siteUrl: String })
const search = ref(props.filters.search || '')
const page = usePage()
const myId = computed(() => page.props.auth.user?.id)

let t
watch(search, (v) => {
  clearTimeout(t)
  t = setTimeout(() => router.get('/admin/users', { search: v }, { preserveState: true, replace: true }), 300)
})

// Регистрации нет: аккаунт заводит админ, пароль придумывает сервер. Приходит
// он один раз (Inertia flash — мимо пропсов и истории браузера) и живёт только
// в этой вкладке, пока карточку не закрыли.
const form = useForm({ email: '', name: '' })
const issued = ref(null)
const copied = ref('')
let copiedTimer

function showIssued({ issued: data }) {
  if (!data) return
  issued.value = data
  copied.value = ''
  // «New password» жмут из списка — карточка наверху страницы.
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function create() {
  form.post('/admin/users', {
    preserveScroll: true,
    onFlash: showIssued,
    onSuccess: () => form.reset(),
  })
}

function newPassword(u) {
  if (confirm(`Generate a new password for ${u.name}? The current one will stop working.`)) {
    router.put(`/admin/users/${u.id}/password`, {}, { preserveScroll: true, onFlash: showIssued })
  }
}

// Готовый текст, чтобы сразу отправить человеку.
const invite = computed(() =>
  [`Sukify: ${props.siteUrl}`, `Почта: ${issued.value?.email}`, `Пароль: ${issued.value?.password}`].join('\n'),
)

async function copy(what, text) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = what
    clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => (copied.value = ''), 2000)
  } catch {
    // Без https буфер обмена недоступен — пароль на экране можно выделить.
  }
}

function toggleBan(u) {
  const verb = u.is_banned ? 'Unban' : 'Ban'
  if (confirm(`${verb} ${u.name}?`)) {
    router.put(`/admin/users/${u.id}/ban`, {}, { preserveScroll: true })
  }
}

function toggleAdmin(u) {
  const verb = u.is_admin ? 'Revoke admin rights from' : 'Make'
  const tail = u.is_admin ? '' : ' an admin'
  if (confirm(`${verb} ${u.name}${tail}?`)) {
    router.put(`/admin/users/${u.id}/admin`, {}, { preserveScroll: true })
  }
}
</script>

<template>
  <Head title="Users" />
  <h1 class="text-2xl font-extrabold mb-6">Users</h1>

  <div class="mb-6 rounded-xl bg-neutral-900 border border-neutral-800 p-5">
    <h2 class="font-bold">New user</h2>
    <p class="text-sm text-neutral-400 mt-1 mb-4">
      Sign-up is closed: accounts are created here. The password is generated and shown once.
    </p>

    <form class="flex flex-wrap items-start gap-3" @submit.prevent="create">
      <div class="w-72">
        <label for="new-email" class="block text-sm text-neutral-400 mb-1">Email</label>
        <input id="new-email" v-model="form.email" type="email" required maxlength="255" autocomplete="off" class="w-full rounded-md bg-neutral-950 border border-neutral-700 px-3 py-2 text-sm outline-none focus:border-accent" />
        <p v-if="form.errors.email" class="text-red-400 text-xs mt-1">{{ form.errors.email }}</p>
      </div>
      <div class="w-56">
        <label for="new-name" class="block text-sm text-neutral-400 mb-1">Nickname</label>
        <input id="new-name" v-model="form.name" required maxlength="255" autocomplete="off" class="w-full rounded-md bg-neutral-950 border border-neutral-700 px-3 py-2 text-sm outline-none focus:border-accent" />
        <p v-if="form.errors.name" class="text-red-400 text-xs mt-1">{{ form.errors.name }}</p>
      </div>
      <button :disabled="form.processing" class="mt-6 rounded-full bg-accent text-black text-sm font-bold px-6 py-2 hover:brightness-110 disabled:opacity-60">
        {{ form.processing ? 'Creating…' : 'Create account' }}
      </button>
    </form>

    <div v-if="issued" class="mt-5 rounded-lg bg-accent/10 border border-accent/40 p-4">
      <div class="flex items-start justify-between gap-4">
        <div class="text-sm text-accent font-semibold">
          Copy the password for {{ issued.name }} now — it won't be shown again.
        </div>
        <button class="text-neutral-400 hover:text-white leading-none" aria-label="Hide password" @click="issued = null">✕</button>
      </div>

      <dl class="grid grid-cols-[auto_1fr] items-baseline gap-x-6 gap-y-1 text-sm mt-3">
        <dt class="text-neutral-400">Email</dt>
        <dd class="select-all break-all">{{ issued.email }}</dd>
        <dt class="text-neutral-400">Handle</dt>
        <dd>@{{ issued.username }}</dd>
        <dt class="text-neutral-400">Password</dt>
        <dd class="select-all font-mono text-lg">{{ issued.password }}</dd>
      </dl>

      <div class="mt-4 flex flex-wrap gap-3">
        <button class="rounded-full bg-accent text-black text-sm font-bold px-5 py-2 hover:brightness-110" @click="copy('password', issued.password)">
          {{ copied === 'password' ? 'Copied' : 'Copy password' }}
        </button>
        <button class="rounded-full border border-neutral-600 text-sm font-semibold px-5 py-2 hover:border-white" @click="copy('invite', invite)">
          {{ copied === 'invite' ? 'Copied' : 'Copy invite text' }}
        </button>
      </div>
    </div>
  </div>

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
            <div v-if="u.username" class="text-xs font-normal text-neutral-500">@{{ u.username }}</div>
          </td>
          <td class="px-4 py-3 text-neutral-400">{{ u.email }}</td>
          <td class="px-4 py-3 text-neutral-400">{{ u.playlists_count }}</td>
          <td class="px-4 py-3 text-neutral-400">{{ u.created_at }}</td>
          <td class="px-4 py-3">
            <span v-if="u.is_banned" class="px-2 py-1 rounded bg-red-500/20 text-red-400 text-xs">banned</span>
            <span v-else class="px-2 py-1 rounded bg-neutral-700 text-neutral-200 text-xs">active</span>
          </td>
          <td class="px-4 py-3 text-right space-x-4 whitespace-nowrap">
            <button v-if="!u.is_admin" class="text-neutral-300 hover:underline" @click="newPassword(u)">New password</button>
            <!-- Себе права не меняем — кнопки нет вовсе. -->
            <button v-if="u.id !== myId" class="hover:underline" :class="u.is_admin ? 'text-yellow-400' : 'text-neutral-300'" @click="toggleAdmin(u)">
              {{ u.is_admin ? 'Revoke admin' : 'Make admin' }}
            </button>
            <button v-if="!u.is_admin" class="hover:underline" :class="u.is_banned ? 'text-accent' : 'text-red-400'" @click="toggleBan(u)">
              {{ u.is_banned ? 'Unban' : 'Ban' }}
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
