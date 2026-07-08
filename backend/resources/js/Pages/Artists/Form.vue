<script setup>
import { Head, useForm, Link } from '@inertiajs/vue3'

const props = defineProps({ artist: Object })

const form = useForm({
  name: props.artist?.name || '',
  bio: props.artist?.bio || '',
  avatar: null,
  banner: null,
})

function submit() {
  if (props.artist) {
    form.transform((d) => ({ ...d, _method: 'put' })).post(`/admin/artists/${props.artist.id}`)
  } else {
    form.post('/admin/artists')
  }
}
</script>

<template>
  <Head :title="artist ? 'Edit artist' : 'New artist'" />
  <div class="max-w-xl">
    <h1 class="text-2xl font-extrabold mb-6">{{ artist ? 'Edit artist' : 'New artist' }}</h1>

    <form @submit.prevent="submit" class="space-y-5">
      <div>
        <label class="block text-xs font-bold mb-1 text-neutral-400">Name</label>
        <input v-model="form.name" class="w-full rounded-md bg-neutral-900 border border-neutral-700 px-3 py-2 outline-none focus:border-accent" />
        <p v-if="form.errors.name" class="text-red-400 text-sm mt-1">{{ form.errors.name }}</p>
      </div>

      <div>
        <label class="block text-xs font-bold mb-1 text-neutral-400">Bio</label>
        <textarea v-model="form.bio" rows="4" class="w-full rounded-md bg-neutral-900 border border-neutral-700 px-3 py-2 outline-none focus:border-accent"></textarea>
      </div>

      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="block text-xs font-bold mb-1 text-neutral-400">Avatar</label>
          <input type="file" accept="image/*" @input="form.avatar = $event.target.files[0]" class="text-sm text-neutral-400" />
        </div>
        <div>
          <label class="block text-xs font-bold mb-1 text-neutral-400">Banner (sets page colours)</label>
          <input type="file" accept="image/*" @input="form.banner = $event.target.files[0]" class="text-sm text-neutral-400" />
        </div>
      </div>

      <div class="flex gap-3 pt-2">
        <button :disabled="form.processing" class="rounded-full bg-accent text-black font-bold px-6 py-2 hover:brightness-110 disabled:opacity-60">Save</button>
        <Link href="/admin/artists" class="rounded-full border border-neutral-700 px-6 py-2 hover:border-white">Cancel</Link>
      </div>
    </form>
  </div>
</template>
