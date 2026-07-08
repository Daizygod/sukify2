<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  modelValue: { default: null },
  accept: { type: String, default: '' },
  label: { type: String, default: 'Choose file' },
  preview: { type: Boolean, default: false },
  currentUrl: { type: String, default: '' },
  hint: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue'])

const input = ref(null)
const fileName = ref('')
const localPreview = ref('')

function pick() {
  input.value?.click()
}
function onChange(e) {
  const f = e.target.files[0] || null
  emit('update:modelValue', f)
  fileName.value = f ? f.name : ''
  if (f && props.preview) localPreview.value = URL.createObjectURL(f)
}
function clear() {
  emit('update:modelValue', null)
  fileName.value = ''
  localPreview.value = ''
  if (input.value) input.value.value = ''
}
const previewSrc = computed(() => localPreview.value || props.currentUrl)
</script>

<template>
  <div class="flex items-start gap-4">
    <button
      v-if="preview"
      type="button"
      class="shrink-0 w-24 h-24 rounded-lg overflow-hidden bg-neutral-800 border border-neutral-700 grid place-items-center hover:border-neutral-500 transition"
      @click="pick"
    >
      <img v-if="previewSrc" :src="previewSrc" class="w-full h-full object-cover" />
      <span v-else class="text-xs text-neutral-500">No image</span>
    </button>

    <div class="flex-1 min-w-0">
      <div class="flex items-center gap-3">
        <button
          type="button"
          class="rounded-full bg-neutral-100 text-black text-sm font-bold px-4 py-2 hover:bg-white transition whitespace-nowrap"
          @click="pick"
        >
          {{ label }}
        </button>
        <span class="text-sm text-neutral-400 truncate">{{ fileName || 'No file selected' }}</span>
        <button v-if="fileName" type="button" class="text-neutral-500 hover:text-white text-sm" @click="clear">Remove</button>
      </div>
      <p v-if="hint" class="text-xs text-neutral-500 mt-2">{{ hint }}</p>
    </div>

    <input ref="input" type="file" :accept="accept" class="hidden" @change="onChange" />
  </div>
</template>
