<script setup>
import { useForm, Head } from '@inertiajs/vue3'

defineOptions({ layout: null })

const form = useForm({ email: '', password: '' })
function submit() {
  form.post('/admin/login')
}
</script>

<template>
  <Head title="Log in" />
  <div class="min-h-screen grid place-items-center bg-gradient-to-b from-neutral-900 to-neutral-950 px-4">
    <div class="w-full max-w-sm bg-black/60 border border-neutral-800 rounded-2xl p-8">
      <div class="text-center text-xl font-extrabold mb-6">🎧 Sukify <span class="text-accent">Admin</span></div>
      <form @submit.prevent="submit" class="space-y-4">
        <div>
          <label class="block text-xs font-bold mb-1 text-neutral-400">Email</label>
          <input v-model="form.email" type="email" class="w-full rounded-md bg-neutral-900 border border-neutral-700 px-3 py-2 focus:border-accent outline-none" />
        </div>
        <div>
          <label class="block text-xs font-bold mb-1 text-neutral-400">Password</label>
          <input v-model="form.password" type="password" class="w-full rounded-md bg-neutral-900 border border-neutral-700 px-3 py-2 focus:border-accent outline-none" />
        </div>
        <p v-if="form.errors.email" class="text-red-400 text-sm">{{ form.errors.email }}</p>
        <button :disabled="form.processing" class="w-full rounded-full bg-accent text-black font-bold py-3 hover:brightness-110 disabled:opacity-60">
          {{ form.processing ? '…' : 'Log In' }}
        </button>
      </form>
      <p class="text-xs text-neutral-500 mt-4 text-center">admin@sukify.test / password</p>
    </div>
  </div>
</template>
