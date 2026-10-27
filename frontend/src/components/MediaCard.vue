<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import { useMenuStore } from '@/stores/menu'
import { usePlayContext } from '@/composables/usePlayContext'

const props = defineProps({
  to: { type: [Object, String], required: true },
  cover: { type: Object, default: null },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  round: { type: Boolean, default: false },
  playable: { type: Boolean, default: true },
  playing: { type: Boolean, default: false },
  // Самодостаточный режим: карточка сама знает свой контекст и треки —
  // иконка ▶/⏸ и клик работают без обвязки на странице.
  contextKey: { type: String, default: '' },
  tracks: { type: [Array, Function], default: null },
  contextName: { type: String, default: '' },
})
const emit = defineEmits(['play'])

const self = usePlayContext({
  key: () => props.contextKey,
  tracks: () => (typeof props.tracks === 'function' ? props.tracks() : props.tracks || []),
  name: () => props.contextName || props.title,
})
const selfManaged = computed(() => !!props.contextKey && !!props.tracks)
const isPlaying = computed(() => (selfManaged.value ? self.isPlaying.value : props.playing))

const menu = useMenuStore()

// Сущность для контекстного меню выводится из ссылки карточки.
const entity = computed(() => {
  const to = props.to
  if (!to || typeof to === 'string') return null
  switch (to.name) {
    case 'release':
      return { type: 'release', slug: to.params.slug, title: props.title }
    case 'playlist':
      return { type: 'playlist', id: to.params.id, title: props.title, pinType: 'playlist', pinId: Number(to.params.id) }
    case 'artist':
      return { type: 'artist', slug: to.params.slug, title: props.title }
    case 'mix':
      return { type: 'mix', n: to.params.n, title: props.title }
    default:
      return null
  }
})

function onContext(e) {
  if (entity.value) menu.openEntityMenu(e, entity.value)
}

function onPlay(e) {
  // Повторный клик даблклика игнорируем — иначе трек тут же встаёт на паузу.
  if (e.detail > 1) return
  if (selfManaged.value) return self.toggle(e)
  emit('play')
}
</script>

<template>
  <RouterLink :to="to" class="card" @contextmenu.prevent="onContext">
    <div class="card__art">
      <CoverImage :cover="cover" :size="300" :rounded="round" :alt="title" />
      <button
        v-if="playable"
        class="play-btn card__play"
        :class="{ 'card__play--visible': isPlaying }"
        :title="isPlaying ? 'Пауза' : 'Слушать'"
        @click.prevent.stop="onPlay"
      >
        <Icon :name="isPlaying ? 'pauseBig' : 'playBig'" :size="24" />
      </button>
    </div>
    <div class="card__title">{{ title }}</div>
    <div v-if="subtitle" class="card__subtitle">{{ subtitle }}</div>
  </RouterLink>
</template>

<style scoped>
.card {
  position: relative;
  isolation: isolate;
  display: block;
  background: transparent;
  border-radius: 6px;
  padding: 12px;
}
/* Подложка при наведении — не плоская: светлее сверху, к низу гаснет.
   Отдельным слоем, чтобы градиент проявлялся плавно (opacity). */
.card::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  border-radius: inherit;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.1) 0%, rgba(255, 255, 255, 0.045) 100%);
  opacity: 0;
  transition: opacity 0.25s ease;
}
.card:hover::before {
  opacity: 1;
}
.card__art {
  position: relative;
  margin-bottom: 10px;
}
.card__art :deep(.cover) {
  transition: box-shadow 0.3s ease;
}
.card:hover .card__art :deep(.cover) {
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.55);
}
.card__play {
  position: absolute;
  right: 8px;
  bottom: 8px;
  opacity: 0;
  transform: translateY(8px);
  transition: opacity 0.25s ease, transform 0.3s var(--ease-spotify), background 0.2s ease;
}
.card:hover .card__play,
.card__play--visible {
  opacity: 1;
  transform: translateY(0);
}
.card:hover .card__play:hover {
  transform: scale(1.06);
}
.card .card__play:active {
  transform: scale(0.95);
}
.card__title {
  font-weight: 700;
  font-size: 16px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.card__subtitle {
  color: var(--text-subdued);
  font-size: 14px;
  margin-top: 4px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
