<script setup>
import { ref, watch } from 'vue'
import api from '@/lib/api'
import Icon from './Icon.vue'
import CoverImage from './CoverImage.vue'
import TransitionModal from './TransitionModal.vue'
import { useUiStore } from '@/stores/ui'
import { formatDuration } from '@/lib/format'

const ui = useUiStore()

const q = ref('')
const results = ref([])
const to = ref(null)
const searching = ref(false)
let timer

watch(q, (v) => {
  clearTimeout(timer)
  if (!v.trim()) {
    results.value = []
    return
  }
  timer = setTimeout(runSearch, 250)
})

async function runSearch() {
  searching.value = true
  try {
    const { data } = await api.get('/search', { params: { q: q.value.trim(), limit: 10 } })
    results.value = data.tracks.filter((t) => t.id !== ui.transitionFrom?.id)
  } finally {
    searching.value = false
  }
}

function swap() {
  const from = ui.transitionFrom
  ui.transitionFrom = to.value
  to.value = from
}

function close() {
  ui.transitionFrom = null
  to.value = null
  q.value = ''
  results.value = []
}
</script>

<template>
  <!-- Шаг 2: пара выбрана — открываем обычный редактор переходов -->
  <TransitionModal
    v-if="to"
    :from="ui.transitionFrom"
    :to="to"
    @close="close"
  />

  <!-- Шаг 1: выбор второго трека -->
  <Teleport v-else to="body">
    <div class="tp__backdrop" @click.self="close">
      <div class="tp">
        <div class="tp__head">
          <div>
            <div class="tp__kicker">Создать переход</div>
            <div class="tp__from">
              <span class="muted">Из:</span>
              <b>{{ ui.transitionFrom.title }}</b>
              <span class="muted">— {{ (ui.transitionFrom.artists || []).map((a) => a.name).join(', ') }}</span>
            </div>
          </div>
          <button class="tp__close" @click="close">
            <Icon name="plus" :size="16" style="transform: rotate(45deg)" />
          </button>
        </div>

        <div class="tp__search">
          <Icon name="searchSmall" :size="16" />
          <input
            v-model="q"
            placeholder="В какой трек переходим? Ищи по названию…"
            autofocus
          />
        </div>

        <div class="tp__results">
          <button v-for="t in results" :key="t.id" class="tp__row" @click="to = t">
            <CoverImage :cover="t.cover" :size="40" class="tp__cover" />
            <div class="tp__meta">
              <div class="tp__title">{{ t.title }}</div>
              <div class="tp__artists muted">{{ (t.artists || []).map((a) => a.name).join(', ') }}</div>
            </div>
            <span class="muted tp__dur">{{ formatDuration(t.duration_ms) }}</span>
          </button>
          <p v-if="q && !searching && !results.length" class="muted tp__none">
            Ничего не нашлось.
          </p>
          <p v-if="!q" class="muted tp__none">
            Подсказка: переход применится, когда эти два трека сыграют подряд —
            в плейлисте, альбоме или очереди.
          </p>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.tp__backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  display: grid;
  place-items: center;
  z-index: 120;
}
.tp {
  width: min(520px, calc(100vw - 32px));
  max-height: 72vh;
  display: flex;
  flex-direction: column;
  background: #1f1f1f;
  border-radius: 8px;
  padding: 24px;
  box-shadow: 0 24px 48px rgba(0, 0, 0, 0.6);
}
.tp__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.tp__kicker {
  color: var(--text-subdued);
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin-bottom: 6px;
}
.tp__from {
  display: flex;
  gap: 6px;
  font-size: 15px;
  flex-wrap: wrap;
}
.tp__close {
  color: var(--text-subdued);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex: 0 0 32px;
}
.tp__close:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.tp__search {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #2a2a2a;
  border: 1px solid transparent;
  border-radius: 6px;
  padding: 10px 12px;
  color: var(--text-subdued);
}
.tp__search:focus-within {
  border-color: #fff;
}
.tp__search input {
  flex: 1;
  background: none;
  border: none;
  outline: none;
  color: #fff;
  font-size: 14px;
}
.tp__results {
  overflow-y: auto;
  min-height: 0;
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.tp__row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px;
  border-radius: 6px;
  text-align: left;
  color: #fff;
}
.tp__row:hover {
  background: rgba(255, 255, 255, 0.08);
}
.tp__cover {
  width: 40px;
  flex: 0 0 40px;
  border-radius: 4px;
}
.tp__meta {
  flex: 1;
  min-width: 0;
}
.tp__title {
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tp__artists {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tp__dur {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.tp__none {
  font-size: 13px;
  padding: 12px 4px;
  line-height: 1.5;
}
</style>
