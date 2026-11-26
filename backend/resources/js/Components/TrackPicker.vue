<script setup>
// Поиск трека каталога: слова ищутся в названии, артисте и альбоме.
import { onBeforeUnmount, ref, watch } from 'vue'
import { formatDuration } from '../lib/bulkUpload.js'

defineProps({ placeholder: { type: String, default: 'Find a track: artist, title, album…' } })
const emit = defineEmits(['pick'])

const q = ref('')
const results = ref([])
const open = ref(false)
let timer

watch(q, (v) => {
  clearTimeout(timer)
  if (v.trim().length < 2) {
    results.value = []
    open.value = false
    return
  }
  timer = setTimeout(search, 250)
})

async function search() {
  const res = await fetch(`/admin/uploads/tracks?q=${encodeURIComponent(q.value.trim())}`, {
    headers: { Accept: 'application/json' },
  })
  results.value = (await res.json()).data
  open.value = true
}

function pick(t) {
  emit('pick', t)
  q.value = ''
  results.value = []
  open.value = false
}

function onBlur() {
  timer = setTimeout(() => (open.value = false), 150)
}
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <div class="relative">
    <input
      v-model="q"
      :placeholder="placeholder"
      class="w-full rounded-md bg-neutral-950 border border-neutral-700 px-3 py-1.5 outline-none focus:border-accent text-sm"
      @focus="results.length && (open = true)"
      @blur="onBlur"
    />
    <div
      v-if="open"
      class="absolute z-20 mt-1 w-full max-h-64 overflow-y-auto rounded-md bg-neutral-800 border border-neutral-700 shadow-xl"
    >
      <button
        v-for="t in results"
        :key="t.id"
        type="button"
        class="block w-full text-left px-3 py-2 text-sm hover:bg-neutral-700"
        @mousedown.prevent="pick(t)"
      >
        <div class="font-semibold truncate">{{ t.title }}</div>
        <div class="text-xs text-neutral-400 truncate">
          {{ t.artists }} · {{ t.release }} · {{ t.full ? formatDuration(t.duration_ms) : t.preview ? 'preview' : 'no audio' }}
        </div>
      </button>
      <div v-if="!results.length" class="px-3 py-2 text-sm text-neutral-500">Nothing found</div>
    </div>
  </div>
</template>
