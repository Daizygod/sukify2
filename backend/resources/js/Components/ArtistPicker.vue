<script setup>
// Поиск исполнителя с подсказками (вместо гигантского <select>).
import { ref, watch, onBeforeUnmount } from 'vue'

const props = defineProps({
  exclude: { type: Array, default: () => [] }, // уже выбранные id
  placeholder: { type: String, default: 'Найти исполнителя…' },
})
const emit = defineEmits(['pick'])

const q = ref('')
const results = ref([])
const open = ref(false)
let timer

watch(q, (v) => {
  clearTimeout(timer)
  if (!v.trim()) {
    results.value = []
    open.value = false
    return
  }
  timer = setTimeout(search, 200)
})

async function search() {
  const res = await fetch(`/admin/artists/lookup?q=${encodeURIComponent(q.value.trim())}`, {
    headers: { Accept: 'application/json' },
  })
  const json = await res.json()
  results.value = json.data.filter((a) => !props.exclude.includes(a.id))
  open.value = true
}

function pick(a) {
  emit('pick', a)
  q.value = ''
  results.value = []
  open.value = false
}

function onBlur() {
  // Даём click по пункту успеть отработать.
  timer = setTimeout(() => (open.value = false), 150)
}
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <div class="relative">
    <input
      v-model="q"
      :placeholder="placeholder"
      class="w-full rounded-md bg-neutral-900 border border-neutral-700 px-3 py-2 outline-none focus:border-accent text-sm"
      @focus="q && (open = true)"
      @blur="onBlur"
    />
    <div
      v-if="open && results.length"
      class="absolute z-20 mt-1 w-full max-h-56 overflow-y-auto rounded-md bg-neutral-800 border border-neutral-700 shadow-xl"
    >
      <button
        v-for="a in results"
        :key="a.id"
        type="button"
        class="block w-full text-left px-3 py-2 text-sm hover:bg-neutral-700"
        @mousedown.prevent="pick(a)"
      >
        {{ a.name }}
      </button>
    </div>
    <div
      v-else-if="open && q.trim()"
      class="absolute z-20 mt-1 w-full rounded-md bg-neutral-800 border border-neutral-700 px-3 py-2 text-sm text-neutral-500"
    >
      Никого не нашлось
    </div>
  </div>
</template>
