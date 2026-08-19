<script setup>
import { ref, watch, computed } from 'vue'
import api from '@/lib/api'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import { useLibraryStore } from '@/stores/library'
import { useToastStore } from '@/stores/toasts'

/**
 * «Изменить данные» плейлиста — та же модалка обслуживает и создание
 * (тогда полей обложки нет: плейлиста ещё не существует).
 */
const props = defineProps({
  // null → режим создания; объект плейлиста → режим редактирования
  playlist: { type: Object, default: null },
})
const emit = defineEmits(['close', 'saved'])

const library = useLibraryStore()
const toasts = useToastStore()

const isCreate = computed(() => !props.playlist)
const title = ref('')
const description = ref('')
const saving = ref(false)
const fileInput = ref(null)
// Локальный предпросмотр выбранной обложки до сохранения.
const pickedFile = ref(null)
const pickedUrl = ref('')

watch(
  () => props.playlist,
  (p) => {
    title.value = p?.title || ''
    description.value = p?.description || ''
    pickedFile.value = null
    pickedUrl.value = ''
  },
  { immediate: true }
)

function pickPhoto() {
  fileInput.value?.click()
}

function onFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  pickedFile.value = file
  pickedUrl.value = URL.createObjectURL(file)
}

async function save() {
  const name = title.value.trim()
  if (!name) return toasts.show('Придумай название')
  saving.value = true
  try {
    if (isCreate.value) {
      const created = await library.createPlaylist(name, description.value.trim())
      emit('saved', created)
    } else {
      const { data } = await api.put(`/playlists/${props.playlist.id}`, {
        title: name,
        description: description.value.trim() || null,
      })
      if (pickedFile.value) {
        const form = new FormData()
        form.append('cover', pickedFile.value)
        const { data: withCover } = await api.post(`/playlists/${props.playlist.id}/cover`, form)
        emit('saved', withCover.data)
      } else {
        emit('saved', data.data)
      }
      await library.refreshPlaylists()
    }
    emit('close')
  } catch (e) {
    toasts.show(e?.response?.status === 422 ? 'Не подошёл файл обложки' : 'Не удалось сохранить')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="pem__backdrop" @click.self="emit('close')">
    <div class="pem">
      <div class="pem__head">
        <h2 class="pem__title">{{ isCreate ? 'Новый плейлист' : 'Изменить данные' }}</h2>
        <button class="pem__close" title="Закрыть" @click="emit('close')">
          <Icon name="close" :size="16" />
        </button>
      </div>

      <div class="pem__body">
        <button v-if="!isCreate" class="pem__cover" title="Выбрать фото" @click="pickPhoto">
          <img v-if="pickedUrl" :src="pickedUrl" alt="" />
          <CoverImage
            v-else-if="playlist.cover_url"
            :cover="{ 640: playlist.cover_url }"
            :size="300"
            :alt="playlist.title"
          />
          <div v-else class="pem__ph"><Icon name="album" :size="48" /></div>
          <span class="pem__coverhint">
            <Icon name="edit" :size="28" />
            <span>Выбрать фото</span>
          </span>
        </button>
        <input ref="fileInput" type="file" accept="image/*" hidden @change="onFile" />

        <div class="pem__fields">
          <input
            v-model="title"
            class="pem__input"
            placeholder="Название"
            maxlength="255"
            @keydown.enter="save"
          />
          <textarea
            v-model="description"
            class="pem__input pem__input--area"
            placeholder="Добавь необязательное описание"
            maxlength="1000"
          ></textarea>
        </div>
      </div>

      <div class="pem__foot">
        <button class="pem__save" :disabled="saving" @click="save">
          {{ saving ? 'Сохраняем…' : isCreate ? 'Создать' : 'Сохранить' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pem__backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  display: grid;
  place-items: center;
  z-index: 130;
}
.pem {
  width: min(524px, calc(100vw - 32px));
  background: #282828;
  border-radius: 8px;
  padding: 24px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.6);
}
.pem__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.pem__title {
  font-size: 24px;
  font-weight: 700;
}
.pem__close {
  color: var(--text-subdued);
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
}
.pem__close:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.pem__body {
  display: flex;
  gap: 16px;
}
.pem__cover {
  position: relative;
  width: 180px;
  height: 180px;
  flex: 0 0 180px;
  border-radius: 4px;
  overflow: hidden;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.5);
  background: #333;
}
.pem__cover :deep(img),
.pem__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.pem__ph {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  color: var(--text-subdued);
}
.pem__coverhint {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  gap: 6px;
  align-content: center;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 13px;
  opacity: 0;
  transition: opacity 0.15s ease;
}
.pem__cover:hover .pem__coverhint {
  opacity: 1;
}
.pem__fields {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.pem__input {
  background: #3e3e3e;
  border: 1px solid transparent;
  border-radius: 4px;
  color: #fff;
  font-size: 14px;
  font-family: inherit;
  padding: 12px;
  width: 100%;
}
.pem__input:focus {
  border-color: #727272;
  outline: none;
}
.pem__input--area {
  flex: 1;
  min-height: 110px;
  resize: none;
}
.pem__foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}
.pem__save {
  background: #fff;
  color: #000;
  font-weight: 700;
  font-size: 15px;
  border-radius: 999px;
  padding: 12px 32px;
}
.pem__save:hover:not(:disabled) {
  transform: scale(1.04);
}
.pem__save:disabled {
  opacity: 0.6;
}

@media (max-width: 768px) {
  .pem__body {
    flex-direction: column;
    align-items: center;
  }
  .pem__cover {
    width: 160px;
    height: 160px;
    flex-basis: 160px;
  }
  .pem__fields {
    width: 100%;
  }
}
</style>
