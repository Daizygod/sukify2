<script setup>
/**
 * Редактор перехода — правая панель. Повторяет оригинал: две волны с
 * перекрытием, карусель пресетов и три списка эффектов, а сверху — «Сохранить».
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from '../Icon.vue'
import CoverImage from '../CoverImage.vue'
import MixWaveform from './MixWaveform.vue'
import api from '@/lib/api'
import { useUiStore } from '@/stores/ui'
import { usePlayerStore } from '@/stores/player'
import { useToastStore } from '@/stores/toasts'
import { camelotColor, CAMELOT_TEXT, camelotTitle, keysCompatible } from '@/lib/mix/camelot'
import {
  VOLUME_SHAPES, EQ_SHAPES, FILTER_SHAPES, PRESETS, PRESET_ORDER,
  CURVE_COLORS, beatsMatch, resolveShapes, autoShapes,
} from '@/lib/mix/shapes'
import { barMs, snapToBeat } from '@/lib/mix/waveform'
import { createPreview } from '@/lib/mix/preview'

const ui = useUiStore()
const player = usePlayerStore()
const toasts = useToastStore()

const from = computed(() => ui.mixPair?.from || null)
const to = computed(() => ui.mixPair?.to || null)

const analysis = ref({ from: null, to: null })
const loading = ref(true)
const saving = ref(false)

// --- Состояние перехода ----------------------------------------------------
const preset = ref('auto')
const volume = ref('crossfade')
const eq = ref('none')
const filter = ref('none')
const bars = ref(4)
const outStartMs = ref(0)
const inStartMs = ref(0)
const lengthMs = ref(6000)

const beatMatched = computed(() =>
  beatsMatch(analysis.value.from?.bpm, analysis.value.to?.bpm)
)
const keyMatched = computed(() =>
  keysCompatible(analysis.value.from?.camelot, analysis.value.to?.camelot)
)

const shapes = computed(() =>
  resolveShapes(
    { preset: preset.value, volume_shape: volume.value, eq_shape: eq.value, filter_shape: filter.value },
    beatMatched.value
  )
)

/** Пресеты: «Свой вариант» встаёт первым, как только тронули руками. */
const presetList = computed(() => {
  const list = PRESET_ORDER.map((k) => ({ key: k, title: PRESETS[k].title }))
  if (preset.value === 'custom') list.unshift({ key: 'custom', title: PRESETS.custom.title })

  return list
})

function markCustom() {
  if (preset.value !== 'custom') preset.value = 'custom'
  ui.mixDirty = true
}

function pickPreset(key) {
  preset.value = key
  const p = PRESETS[key]
  const s = p.auto ? autoShapes(beatMatched.value) : p
  if (s.volume) {
    volume.value = s.volume
    eq.value = s.eq
    filter.value = s.filter
  }
  ui.mixDirty = true
}

// --- Загрузка --------------------------------------------------------------

async function load() {
  if (!from.value || !to.value) return
  loading.value = true
  try {
    const [{ data: a }, { data: t }] = await Promise.all([
      api.get('/analysis/pair', { params: { from: from.value.id, to: to.value.id } }),
      api.get('/transitions', { params: { from: from.value.id, to: to.value.id } }),
    ])
    analysis.value = a.data || { from: null, to: null }
    applyTransition(t.data)
  } catch {
    analysis.value = { from: null, to: null }
    applyTransition(null)
  } finally {
    loading.value = false
    ui.mixDirty = false
  }
}

/** Разворачивает сохранённый переход в состояние формы либо ставит умолчания. */
function applyTransition(t) {
  const dur = analysis.value.from?.duration_ms || from.value?.duration_ms || 0
  const matched = beatsMatch(analysis.value.from?.bpm, analysis.value.to?.bpm)

  if (t) {

    preset.value = t.preset || 'custom'
    volume.value = t.volume_shape || 'crossfade'
    eq.value = t.eq_shape || 'none'
    filter.value = t.filter_shape || 'none'
    bars.value = t.bars || 4
    outStartMs.value = t.fade_out_start_ms
    inStartMs.value = t.fade_in_start_ms
    lengthMs.value = Math.max(500, t.fade_out_end_ms - t.fade_out_start_ms)

    return
  }


  preset.value = 'auto'
  const s = autoShapes(matched)
  volume.value = s.volume
  eq.value = s.eq
  filter.value = s.filter
  bars.value = 4
  // Всё в целых миллисекундах: такт при 151 BPM — дробный, а на бэк уходят
  // целые поля, и валидация справедливо ругалась.
  lengthMs.value = matched && analysis.value.from?.bpm
    ? Math.round(barMs(analysis.value.from.bpm) * 4)
    : 6000
  // По умолчанию перекрытие висит на самом хвосте уходящего трека и на первых
  // секундах нового — как и подставляет оригинал.
  outStartMs.value = Math.max(0, Math.round(dur - lengthMs.value))
  inStartMs.value = Math.round(analysis.value.to?.beat_offset_ms || 0)
}

/** Перекрытие не должно вылезать за конец трека — после смены тактов особенно. */
function clampStarts() {
  const outMax = Math.max(0, (analysis.value.from?.duration_ms || 0) - lengthMs.value)
  const inMax = Math.max(0, (analysis.value.to?.duration_ms || 0) - lengthMs.value)
  outStartMs.value = Math.min(outStartMs.value, outMax)
  inStartMs.value = Math.min(inStartMs.value, inMax)
}

watch(() => [from.value?.id, to.value?.id], load, { immediate: true })

// Такты пересчитывают длину перекрытия по темпу уходящего трека.
watch([bars, beatMatched], () => {
  if (!beatMatched.value || !analysis.value.from?.bpm) return
  lengthMs.value = Math.round(barMs(analysis.value.from.bpm) * bars.value)
  clampStarts()
})

function setBars(v) {
  bars.value = v
  markCustom()
}

/** Границы прилипают к долям — иначе перекрытие уезжает с ритма. */
function setOutStart(ms) {
  const dur = analysis.value.from?.duration_ms || 0
  const max = Math.max(0, dur - lengthMs.value)
  const snapped = beatMatched.value ? snapToBeat(analysis.value.from?.beats, ms) : ms
  outStartMs.value = Math.max(0, Math.round(Math.min(max, snapped)))
  markCustom()
}
function setInStart(ms) {
  const dur = analysis.value.to?.duration_ms || 0
  const max = Math.max(0, dur - lengthMs.value)
  const snapped = beatMatched.value ? snapToBeat(analysis.value.to?.beats, ms) : ms
  inStartMs.value = Math.max(0, Math.round(Math.min(max, snapped)))
  markCustom()
}

// --- Клавиатура ------------------------------------------------------------
// Раскладка снята с оригинала: стрелки — секунда, Shift — десять,
// Alt — десять миллисекунд, PageUp/Down — десять секунд, Home/End — края.

function nudge(side, e) {
  const map = { ArrowLeft: -1, ArrowRight: 1, PageDown: -10, PageUp: 10 }
  const dir = map[e.key]
  const dur = (side === 'out' ? analysis.value.from : analysis.value.to)?.duration_ms || 0

  if (e.key === 'Home' || e.key === 'End') {
    e.preventDefault()
    const v = e.key === 'Home' ? 0 : Math.max(0, dur - lengthMs.value)
    side === 'out' ? setOutStart(v) : setInStart(v)

    return
  }
  if (dir === undefined) return
  e.preventDefault()
  let step = 1000
  if (e.shiftKey) step = 10000
  else if (e.altKey) step = 10
  if (e.key.startsWith('Page')) step = 1000
  const cur = side === 'out' ? outStartMs.value : inStartMs.value
  const next = Math.max(0, cur + dir * step)
  side === 'out' ? setOutStart(next) : setInStart(next)
}

// --- Предпрослушка ---------------------------------------------------------

const preview = createPreview()
const previewing = ref(false)
const playhead = ref(-1)
preview.onProgress = (p) => (playhead.value = p)
preview.onFinished = () => (previewing.value = false)

function togglePreview() {
  if (previewing.value) {
    preview.stop()
    previewing.value = false

    return
  }
  if (player.isPlaying) player.togglePlay()
  previewing.value = true
  preview.play({
    fromTrack: from.value,
    toTrack: to.value,
    outStartMs: outStartMs.value,
    inStartMs: inStartMs.value,
    lengthMs: lengthMs.value,
    shapes: shapes.value,
  })
}

onBeforeUnmount(() => preview.destroy())
onMounted(() => {
  window.addEventListener('keydown', onEsc)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onEsc))
function onEsc(e) {
  if (e.key === 'Escape' && previewing.value) togglePreview()
}

// --- Сохранение ------------------------------------------------------------

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    await api.post('/transitions', {
      from_track_id: from.value.id,
      to_track_id: to.value.id,
      fade_out_start_ms: Math.round(outStartMs.value),
      fade_out_end_ms: Math.round(outStartMs.value + lengthMs.value),
      fade_in_start_ms: Math.round(inStartMs.value),
      fade_in_full_volume_ms: Math.round(inStartMs.value + lengthMs.value),
      curve_type: 'equal_power',
      preset: preset.value,
      volume_shape: shapes.value.volume,
      eq_shape: shapes.value.eq,
      filter_shape: shapes.value.filter,
      bars: beatMatched.value ? bars.value : null,
    })
    player.invalidateTransitions()
    ui.mixDirty = false
    ui.playlistRevision++
    toasts.show('Переход сохранён')
  } catch (e) {
    toasts.show(e?.response?.data?.message || 'Не удалось сохранить переход')
  } finally {
    saving.value = false
  }
}

function close() {
  if (ui.mixDirty && !window.confirm('Отменить изменения? Если выйти сейчас, изменения не сохранятся.')) return
  preview.stop()
  ui.closeMixEditor()
}

// --- Списки эффектов -------------------------------------------------------

const openRow = ref(null)
const rows = computed(() => [
  { key: 'volume', title: 'Громкость', model: volume, table: VOLUME_SHAPES, color: CURVE_COLORS.volume },
  { key: 'eq', title: 'Эквалайзер', model: eq, table: EQ_SHAPES, color: CURVE_COLORS.eq },
  { key: 'filter', title: 'Фильтр', model: filter, table: FILTER_SHAPES, color: CURVE_COLORS.filter },
])

function pickShape(row, key) {
  row.model.value = key
  markCustom()
  openRow.value = null
}

function bpmText(a) {
  return a?.bpm ? `${Math.round(a.bpm)} BPM` : '— BPM'
}
</script>

<template>
  <aside class="mx" aria-label="Изменение перехода">
    <header class="mx__top">
      <h1 class="mx__title">Изменение перехода</h1>
      <span class="mx__beta">Бета-версия</span>
      <div class="mx__actions">
        <button class="mx__save" :disabled="saving" @click="save">
          {{ saving ? 'Сохраняем…' : 'Сохранить' }}
        </button>
        <button class="mx__icon" title="Закрыть" @click="close">
          <Icon name="close" :size="16" />
        </button>
      </div>
    </header>

    <div v-if="loading" class="mx__empty">Считаем волну…</div>

    <div v-else class="mx__body">
      <div class="mx__track">
        <CoverImage :cover="from?.cover" :size="40" class="mx__cover" />
        <span class="mx__name">{{ from?.title }}</span>
        <span class="mx__sep">•</span>
        <span class="mx__artist">{{ (from?.artists || []).map((a) => a.name).join(', ') }}</span>
        <span class="mx__spacer"></span>
        <span class="mx__bpm" :class="{ 'mx__bpm--warn': !beatMatched }" :title="beatMatched ? 'Ритмы треков подходят друг другу' : 'Ритмы треков не подходят друг другу'">
          <Icon v-if="!beatMatched" name="warning" :size="12" />
          {{ bpmText(analysis.from) }}
        </span>
        <span
          v-if="analysis.from?.camelot"
          class="mx__key"
          :style="{ background: camelotColor(analysis.from.camelot), color: CAMELOT_TEXT }"
          :title="camelotTitle(analysis.from.camelot, analysis.from.musical_key, analysis.from.musical_scale)"
        >{{ analysis.from.camelot }}</span>
      </div>

      <MixWaveform
        :from="analysis.from"
        :to="analysis.to"
        :out-start-ms="outStartMs"
        :in-start-ms="inStartMs"
        :length-ms="lengthMs"
        :shapes="shapes"
        :bars="bars"
        :beat-matched="beatMatched"
        :playhead="playhead"
        :previewing="previewing"
        @update:outStartMs="setOutStart"
        @update:inStartMs="setInStart"
        @bars="setBars"
        @preview="togglePreview"
        @nudge="nudge"
      />

      <div class="mx__track mx__track--in">
        <CoverImage :cover="to?.cover" :size="40" class="mx__cover" />
        <span class="mx__name">{{ to?.title }}</span>
        <span class="mx__sep">•</span>
        <span class="mx__artist">{{ (to?.artists || []).map((a) => a.name).join(', ') }}</span>
        <span class="mx__spacer"></span>
        <span class="mx__bpm" :class="{ 'mx__bpm--warn': !beatMatched }">
          <Icon v-if="!beatMatched" name="warning" :size="12" />
          {{ bpmText(analysis.to) }}
        </span>
        <span
          v-if="analysis.to?.camelot"
          class="mx__key"
          :style="{ background: camelotColor(analysis.to.camelot), color: CAMELOT_TEXT }"
          :title="camelotTitle(analysis.to.camelot, analysis.to.musical_key, analysis.to.musical_scale)"
        >{{ analysis.to.camelot }}</span>
      </div>

      <p v-if="keyMatched" class="mx__hint">Тональности сочетаются — переход прозвучит чисто.</p>

      <div class="mx__presets">
        <span class="mx__label">Пресеты</span>
        <div class="mx__chips" role="listbox" aria-label="Пресеты">
          <button
            v-for="p in presetList"
            :key="p.key"
            class="mx__chip"
            :class="{ on: p.key === preset }"
            role="option"
            :aria-selected="p.key === preset"
            @click="pickPreset(p.key)"
          >
            <Icon v-if="p.key === 'auto'" name="sparkle" :size="12" />
            {{ p.title }}
          </button>
        </div>
      </div>

      <div class="mx__rows">
        <div v-for="row in rows" :key="row.key" class="mx__rowwrap">
          <button class="mx__row" :aria-expanded="openRow === row.key" @click="openRow = openRow === row.key ? null : row.key">
            <span class="mx__dot" :style="{ background: row.color }"></span>
            <span class="mx__rowtitle">{{ row.title }}</span>
            <span class="mx__rowvalue">{{ row.table[row.model.value]?.title || '—' }}</span>
            <Icon name="caretDown" :size="14" />
          </button>
          <div v-if="openRow === row.key" class="mx__menu">
            <button
              v-for="(shape, key) in row.table"
              :key="key"
              class="mx__item"
              :class="{ on: key === row.model.value }"
              @click="pickShape(row, key)"
            >
              {{ shape.title }}
              <Icon v-if="key === row.model.value" name="check" :size="14" />
            </button>
          </div>
        </div>
      </div>

      <p v-if="!beatMatched" class="mx__warn">
        Ритмы треков не сходятся, поэтому длину нельзя задать в тактах — тяни границы вручную.
      </p>
    </div>
  </aside>
</template>

<style scoped>
.mx {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-elevated);
  border-radius: var(--radius);
  overflow: hidden;
}
.mx__top {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px;
}
.mx__title {
  font-size: 15px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mx__beta {
  background: var(--accent);
  color: #000;
  font-size: 10px;
  font-weight: 800;
  border-radius: 3px;
  padding: 2px 5px;
}
.mx__actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.mx__save {
  background: var(--accent);
  color: #000;
  font-size: 13px;
  font-weight: 700;
  border-radius: 999px;
  padding: 7px 16px;
}
.mx__save:hover:not(:disabled) {
  background: var(--accent-hover);
}
.mx__save:disabled {
  opacity: 0.6;
}
.mx__icon {
  color: var(--text-subdued);
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 50%;
}
.mx__icon:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.mx__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 16px 24px;
}
.mx__empty {
  padding: 40px 16px;
  color: var(--text-subdued);
}
.mx__track {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  padding: 10px 0;
  min-width: 0;
}
.mx__track--in {
  padding-top: 22px;
}
.mx__cover {
  width: 28px;
  flex: 0 0 28px;
  border-radius: 3px;
}
.mx__name {
  color: #fff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 42%;
}
.mx__sep,
.mx__artist {
  color: var(--text-subdued);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mx__spacer {
  flex: 1;
}
.mx__bpm {
  display: flex;
  align-items: center;
  gap: 3px;
  color: var(--text-subdued);
  white-space: nowrap;
}
.mx__bpm--warn {
  color: #f5a524;
}
.mx__key {
  font-size: 11px;
  font-weight: 700;
  border-radius: 3px;
  padding: 2px 5px;
}
.mx__hint {
  font-size: 12px;
  color: var(--accent);
  margin: 14px 0 0;
}
.mx__warn {
  font-size: 12px;
  color: var(--text-subdued);
  margin: 16px 0 0;
}
.mx__presets {
  margin-top: 22px;
}
.mx__label {
  display: block;
  text-align: center;
  font-size: 11px;
  color: var(--text-subdued);
  margin-bottom: 10px;
}
.mx__chips {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  scrollbar-width: none;
  padding-bottom: 4px;
}
.mx__chips::-webkit-scrollbar {
  display: none;
}
.mx__chip {
  display: flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  font-size: 13px;
  border-radius: 999px;
  padding: 8px 14px;
  white-space: nowrap;
}
.mx__chip:hover {
  background: rgba(255, 255, 255, 0.16);
}
.mx__chip.on {
  background: #fff;
  color: #000;
}
.mx__rows {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mx__rowwrap {
  position: relative;
}
.mx__row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  background: rgba(255, 255, 255, 0.06);
  border-radius: 6px;
  padding: 12px 14px;
  color: #fff;
  font-size: 13px;
}
.mx__row:hover {
  background: rgba(255, 255, 255, 0.1);
}
.mx__dot {
  width: 3px;
  height: 16px;
  border-radius: 2px;
  flex: 0 0 3px;
}
.mx__rowvalue {
  margin-left: auto;
  color: var(--text-subdued);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 55%;
}
.mx__menu {
  position: absolute;
  left: 0;
  right: 0;
  top: calc(100% + 4px);
  z-index: 10;
  background: #282828;
  border-radius: 6px;
  padding: 4px;
  max-height: 300px;
  overflow-y: auto;
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.7);
}
.mx__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 9px 10px;
  border-radius: 4px;
  font-size: 13px;
  color: #fff;
  text-align: left;
}
.mx__item:hover {
  background: rgba(255, 255, 255, 0.1);
}
.mx__item.on {
  color: var(--accent);
}
</style>
