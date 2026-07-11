<script setup>
import { Head, useForm, usePage } from '@inertiajs/vue3'

const page = usePage()
const form = useForm({
  current_password: '',
  password: '',
  password_confirmation: '',
})

function submit() {
  form.put('/admin/profile/password', {
    preserveScroll: true,
    onSuccess: () => form.reset(),
  })
}
</script>

<template>
  <Head title="My profile" />
  <h1 class="text-2xl font-extrabold mb-6">My profile</h1>

  <div class="max-w-md space-y-6">
    <div class="rounded-xl bg-neutral-900 border border-neutral-800 p-5">
      <div class="text-sm text-neutral-400">Signed in as</div>
      <div class="font-semibold mt-1">{{ page.props.auth.user.name }}</div>
      <div class="text-sm text-neutral-400">{{ page.props.auth.user.email }}</div>
    </div>

    <form class="rounded-xl bg-neutral-900 border border-neutral-800 p-5 space-y-4" @submit.prevent="submit">
      <h2 class="font-bold">Change password</h2>

      <div>
        <label class="block text-sm text-neutral-400 mb-1">Current password</label>
        <input v-model="form.current_password" type="password" required class="w-full rounded-md bg-neutral-950 border border-neutral-700 px-3 py-2 text-sm outline-none focus:border-accent" />
        <p v-if="form.errors.current_password" class="text-red-400 text-xs mt-1">{{ form.errors.current_password }}</p>
      </div>

      <div>
        <label class="block text-sm text-neutral-400 mb-1">New password (min. 8 characters)</label>
        <input v-model="form.password" type="password" required minlength="8" class="w-full rounded-md bg-neutral-950 border border-neutral-700 px-3 py-2 text-sm outline-none focus:border-accent" />
        <p v-if="form.errors.password" class="text-red-400 text-xs mt-1">{{ form.errors.password }}</p>
      </div>

      <div>
        <label class="block text-sm text-neutral-400 mb-1">Repeat new password</label>
        <input v-model="form.password_confirmation" type="password" required class="w-full rounded-md bg-neutral-950 border border-neutral-700 px-3 py-2 text-sm outline-none focus:border-accent" />
      </div>

      <button :disabled="form.processing" class="rounded-full bg-accent text-black font-bold px-6 py-2.5 hover:brightness-110 disabled:opacity-60">
        {{ form.processing ? 'Saving…' : 'Update password' }}
      </button>
    </form>
  </div>
</template>
