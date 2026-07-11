<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '@/lib/api'
import Icon from './Icon.vue'
import { usePlayerStore } from '@/stores/player'
import { useToastStore } from '@/stores/toasts'
import { useAuthStore } from '@/stores/auth'
import { formatDuration } from '@/lib/format'
import { buildCurve } from '@/lib/curves'

const props = defineProps({
  from: { type: Object, required: true },
  to: { type: Object, required: true },
})
const emit = defineEmits(['close', 'changed'])

const player = usePlayerStore()
const toasts = useToastStore()
const auth = useAuthStore()

const items = ref([])
const loading = ref(true)
const editorOpen = ref(false)
const saving = ref(false)

const CURVES = [
  { value: 'equal_power', label: 'Равная мощность (как в Spotify)' },
  { value: 's_curve', label: 'S-кривая' },
  { value: 'linear', label: 'Линейная' },
  { value: 'exponential', label: 'Экспонента' },
  { value: 'logarithmic', label: 'Логарифм' },
]
const curveLabel = (v) => CURVES.find((c) => c.value === v)?.label || v

// Editor works in human seconds; the API wants absolute ms in each track.
const form = ref({
  beforeEnd: 7, // сек до конца трека A, когда начинается затухание
  outLen: 7, // длительность затухания
  inStart: 0, // с какой секунды начинается трек B
  inLen: 7, // за сколько секунд B доходит до полной громкости
  curve: 'equal_power',
})

// «Как в Spotify» — один параметр (длительность), кривая равной мощности.
const mode = ref('simple')
const simpleLen = ref(7)

/** Effective envelope for the diagram and for save(). */
const eff = computed(() =>
  mode.value === 'simple'
    ? { beforeEnd: simpleLen.value, outLen: simpleLen.value, inStart: 0, inLen: simpleLen.value, curve: 'equal_power' }
    : { ...form.value }
)

const durA = computed(() => props.from.duration_ms || 0)

// --- диаграмма перехода (стиль эквалайзера Spotify) ----------------------

const CH = { w: 460, h: 158, padL: 38, padR: 12, padT: 14, padB: 26 }

const chart = computed(() => {
  const p = eff.value
  const outLen = Math.min(Math.max(+p.outLen || 0.5, 0.5), 30)
  const inLen = Math.min(Math.max(+p.inLen || 0.5, 0.5), 30)
  const span = Math.max(outLen, inLen)
  const iw = CH.w - CH.padL - CH.padR
  const ih = CH.h - CH.padT - CH.padB
  const x = (sec) => CH.padL + (sec / span) * iw
  const y = (g) => CH.padT + (1 - g) * ih
  const y0 = y(0)

  const line = (from, to, len) => {
    const vals = buildCurve(from, to, p.curve, 48)
    const pts = vals.map((v, i) => [x((i / 47) * len), y(v)])
    if (len < span - 0.001) pts.push([x(span), y(to)]) // дальше громкость не меняется
    return pts.map(([px, py], i) => `${i ? 'L' : 'M'}${px.toFixed(1)},${py.toFixed(1)}`).join(' ')
  }
  const area = (d) => `${d} L${x(span).toFixed(1)},${y0.toFixed(1)} L${x(0).toFixed(1)},${y0.toFixed(1)} Z`

  const out = line(1, 0, outLen)
  const inn = line(0, 1, inLen)

  const step = span <= 12 ? 1 : Math.ceil(span / 10)
  const ticks = []
  for (let s = 0; s <= span + 0.001; s += step) ticks.push({ sec: Math.round(s * 10) / 10, x: x(s) })

  return { out, inn, areaOut: area(out), areaIn: area(inn), ticks, span }
})

/** Мини-график для перехода из списка. */
function miniPaths(t) {
  const outLen = Math.max(t.fade_out_end_ms - t.fade_out_start_ms, 1)
  const inLen = Math.max(t.fade_in_full_volume_ms - t.fade_in_start_ms, 1)
  const span = Math.max(outLen, inLen)
  const w = 72
  const h = 26
  const pad = 2
  const x = (ms) => pad + (ms / span) * (w - 2 * pad)
  const y = (g) => pad + (1 - g) * (h - 2 * pad)
  const mk = (from, to, len) => {
    const vals = buildCurve(from, to, t.curve_type, 24)
    const pts = vals.map((v, i) => [x((i / 23) * len), y(v)])
    if (len < span) pts.push([x(span), y(to)])
    return pts.map(([px, py], i) => `${i ? 'L' : 'M'}${px.toFixed(1)},${py.toFixed(1)}`).join(' ')
  }
  return { out: mk(1, 0, outLen), inn: mk(0, 1, inLen) }
}

async function load() {
  loading.value = true
  try {
    const { data } = await api.get('/transitions/all', {
      params: { from: props.from.id, to: props.to.id },
    })
    items.value = data.data
  } finally {
    loading.value = false
  }
}
onMounted(load)

function describe(t) {
  const before = Math.max(0, Math.round((durA.value - t.fade_out_start_ms) / 100) / 10)
  const outLen = Math.round((t.fade_out_end_ms - t.fade_out_start_ms) / 100) / 10
  const inStart = Math.round(t.fade_in_start_ms / 100) / 10
  const inLen = Math.round((t.fade_in_full_volume_ms - t.fade_in_start_ms) / 100) / 10
  return `затухание ${outLen} c (за ${before} c до конца) → вход с ${inStart} c, подъём ${inLen} c`
}

/** «Использовать этот» — личный выбор, перекрывает общий для меня. */
async function togglePrefer(t) {
  if (!auth.isAuthenticated) return
  try {
    if (t.is_preferred) {
      await api.delete(`/transitions/${t.id}/prefer`)
      items.value.forEach((x) => (x.is_preferred = false))
      toasts.show('Снова используется переход сообщества')
    } else {
      await api.post(`/transitions/${t.id}/prefer`)
      items.value.forEach((x) => (x.is_preferred = x.id === t.id))
      toasts.show('Теперь для тебя играет этот переход')
    }
    player.invalidateTransitions()
    emit('changed')
  } catch {
    toasts.show('Не получилось сохранить выбор')
  }
}

/** Автор может удалить свой переход. */
async function removeTransition(t) {
  if (!confirm('Удалить этот переход?')) return
  try {
    await api.delete(`/transitions/${t.id}`)
    items.value = items.value.filter((x) => x.id !== t.id)
    player.invalidateTransitions()
    emit('changed')
    toasts.show('Переход удалён')
  } catch {
    toasts.show('Удалять можно только свои переходы')
  }
}

async function toggleLike(t) {
  if (!auth.isAuthenticated) return
  try {
    if (t.is_liked) {
      const { data } = await api.delete(`/transitions/${t.id}/like`)
      t.is_liked = false
      t.likes_count = data.likes_count
    } else {
      const { data } = await api.post(`/transitions/${t.id}/like`)
      t.is_liked = true
      t.likes_count = data.likes_count
    }
    player.invalidateTransitions()
    emit('changed')
  } catch {
    toasts.show('Не получилось проголосовать')
  }
}

async function save() {
  const f = eff.value
  const fadeOutStart = Math.max(0, durA.value - f.beforeEnd * 1000)
  const payload = {
    from_track_id: props.from.id,
    to_track_id: props.to.id,
    fade_out_start_ms: Math.round(fadeOutStart),
    fade_out_end_ms: Math.round(Math.min(fadeOutStart + f.outLen * 1000, durA.value)),
    fade_in_start_ms: Math.round(f.inStart * 1000),
    fade_in_full_volume_ms: Math.round((f.inStart + f.inLen) * 1000),
    curve_type: f.curve,
  }
  saving.value = true
  try {
    await api.post('/transitions', payload)
    toasts.show('Переход сохранён — голосуй, чтобы он стал основным')
    editorOpen.value = false
    player.invalidateTransitions()
    emit('changed')
    await load()
  } catch {
    toasts.show('Не получилось сохранить переход')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <!-- Клик по фону не закрывает — только крестик, чтобы не терять настройку. -->
    <div class="tm__backdrop">
      <div class="tm">
        <div class="tm__head">
          <div>
            <div class="tm__kicker">Переход между треками</div>
            <div class="tm__pair">
              <span class="tm__track">{{ from.title }}</span>
              <Icon name="shuffle" :size="14" class="tm__arrow" />
              <span class="tm__track">{{ to.title }}</span>
            </div>
          </div>
          <button class="tm__close" @click="emit('close')">
            <Icon name="plus" :size="16" style="transform: rotate(45deg)" />
          </button>
        </div>

        <p class="tm__hint muted">
          Лучший переход сообщества применяется автоматически, когда треки играют подряд.
        </p>

        <div v-if="loading" class="tm__loading muted">Загружаю…</div>

        <div v-else class="tm__list">
          <div v-for="(t, i) in items" :key="t.id" class="tm__item" :class="{ 'tm__item--best': t.is_preferred || (i === 0 && !items.some((x) => x.is_preferred)) }">
            <svg class="tm__mini" viewBox="0 0 72 26" aria-hidden="true">
              <path :d="miniPaths(t).out" class="tm__line tm__line--out" />
              <path :d="miniPaths(t).inn" class="tm__line tm__line--in" />
            </svg>
            <div class="tm__item-main">
              <div class="tm__item-title">
                {{ curveLabel(t.curve_type) }}
                <span v-if="t.is_preferred" class="tm__badge tm__badge--mine">Твой выбор</span>
                <span v-else-if="i === 0" class="tm__badge">Основной</span>
              </div>
              <div class="tm__item-desc">{{ describe(t) }}</div>
            </div>
            <button
              v-if="auth.isAuthenticated"
              class="tm__use"
              :class="{ on: t.is_preferred }"
              :title="t.is_preferred ? 'Вернуться к переходу сообщества' : 'Использовать этот переход для себя'"
              @click="togglePrefer(t)"
            >
              <Icon name="checkCircle" :size="14" />
              <span>{{ t.is_preferred ? 'Выбран' : 'Использовать' }}</span>
            </button>
            <button
              class="tm__like"
              :class="{ on: t.is_liked }"
              :title="t.is_liked ? 'Убрать голос' : 'Голосовать за переход'"
              @click="toggleLike(t)"
            >
              <Icon :name="t.is_liked ? 'heartFill' : 'heart'" :size="14" />
              <span>{{ t.likes_count }}</span>
            </button>
            <button
              v-if="t.is_mine"
              class="tm__del"
              title="Удалить свой переход"
              @click="removeTransition(t)"
            >
              <Icon name="plus" :size="14" style="transform: rotate(45deg)" />
            </button>
          </div>

          <p v-if="!items.length" class="muted tm__empty">
            Для этой пары пока нет переходов — создай первый!
          </p>
        </div>

        <button v-if="!editorOpen && auth.isAuthenticated" class="tm__create" @click="editorOpen = true">
          <Icon name="plus" :size="14" />
          <span>Создать свой переход</span>
        </button>

        <form v-if="editorOpen" class="tm__editor" @submit.prevent="save">
          <div class="tm__modes">
            <button type="button" :class="{ on: mode === 'simple' }" @click="mode = 'simple'">Как в Spotify</button>
            <button type="button" :class="{ on: mode === 'advanced' }" @click="mode = 'advanced'">Продвинутый</button>
          </div>

          <!-- Диаграмма громкости: перерисовывается при каждом изменении параметров. -->
          <div class="tm__chart">
            <svg :viewBox="`0 0 ${CH.w} ${CH.h}`" class="tm__svg">
              <line
                v-for="tk in chart.ticks"
                :key="'g' + tk.sec"
                :x1="tk.x" :y1="CH.padT" :x2="tk.x" :y2="CH.h - CH.padB"
                class="tm__gridline"
              />
              <line :x1="CH.padL" :y1="CH.padT" :x2="CH.w - CH.padR" :y2="CH.padT" class="tm__gridline" />
              <line :x1="CH.padL" :y1="CH.h - CH.padB" :x2="CH.w - CH.padR" :y2="CH.h - CH.padB" class="tm__gridline tm__gridline--base" />

              <text :x="CH.padL - 6" :y="CH.padT + 4" class="tm__axis" text-anchor="end">100%</text>
              <text :x="CH.padL - 6" :y="CH.h - CH.padB + 4" class="tm__axis" text-anchor="end">0</text>
              <text
                v-for="tk in chart.ticks"
                :key="'t' + tk.sec"
                :x="tk.x" :y="CH.h - 8"
                class="tm__axis" text-anchor="middle"
              >{{ tk.sec }}с</text>

              <path :d="chart.areaOut" class="tm__area tm__area--out" />
              <path :d="chart.areaIn" class="tm__area tm__area--in" />
              <path :d="chart.out" class="tm__line tm__line--out" />
              <path :d="chart.inn" class="tm__line tm__line--in" />
            </svg>
            <div class="tm__legend">
              <span><i class="tm__chip tm__chip--out"></i>{{ from.title }} — затухает</span>
              <span>
                <i class="tm__chip tm__chip--in"></i>{{ to.title }} — нарастает<template v-if="mode === 'advanced' && form.inStart > 0"> (вход с {{ form.inStart }} с)</template>
              </span>
            </div>
          </div>

          <label v-if="mode === 'simple'" class="tm__simple">
            <span>Длительность перехода, сек</span>
            <div class="tm__simple-row">
              <input v-model.number="simpleLen" type="range" min="1" max="12" step="1" />
              <b class="tm__simple-val">{{ simpleLen }} с</b>
            </div>
            <span class="muted tm__simple-hint">
              За {{ simpleLen }} с до конца трек начнёт затухать, следующий войдёт одновременно — по кривой равной мощности, как в Spotify.
            </span>
          </label>

          <template v-else>
            <div class="tm__grid">
              <label>
                <span>Начать затухание за, сек до конца</span>
                <input v-model.number="form.beforeEnd" type="number" min="1" max="30" step="0.5" required />
              </label>
              <label>
                <span>Длительность затухания, сек</span>
                <input v-model.number="form.outLen" type="number" min="0.5" max="30" step="0.5" required />
              </label>
              <label>
                <span>Вход следующего с, сек</span>
                <input v-model.number="form.inStart" type="number" min="0" max="60" step="0.5" required />
              </label>
              <label>
                <span>Подъём громкости, сек</span>
                <input v-model.number="form.inLen" type="number" min="0.5" max="30" step="0.5" required />
              </label>
            </div>
            <label class="tm__curve">
              <span>Кривая громкости</span>
              <select v-model="form.curve">
                <option v-for="c in CURVES" :key="c.value" :value="c.value">{{ c.label }}</option>
              </select>
            </label>
          </template>
          <div class="tm__actions">
            <button type="button" class="tm__cancel" @click="editorOpen = false">Отмена</button>
            <button type="submit" class="btn-primary tm__save" :disabled="saving">
              {{ saving ? 'Сохраняю…' : 'Сохранить переход' }}
            </button>
          </div>
          <p class="tm__editor-hint muted">
            Длина «{{ from.title }}»: {{ formatDuration(from.duration_ms) }}
          </p>
        </form>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.tm__backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.7);
  display: grid;
  place-items: center;
  z-index: 120;
}
.tm {
  width: min(520px, calc(100vw - 32px));
  max-height: 80vh;
  overflow-y: auto;
  background: #1f1f1f;
  border-radius: 8px;
  padding: 24px;
  box-shadow: 0 24px 48px rgba(0, 0, 0, 0.6);
}
.tm__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.tm__kicker {
  color: var(--text-subdued);
  font-size: 12px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin-bottom: 6px;
}
.tm__pair {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 18px;
  font-weight: 700;
  flex-wrap: wrap;
}
.tm__arrow {
  color: var(--accent);
  flex: 0 0 auto;
}
.tm__close {
  color: var(--text-subdued);
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex: 0 0 32px;
}
.tm__close:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.tm__hint {
  font-size: 14px;
  margin: 10px 0 16px;
}
.tm__loading,
.tm__empty {
  font-size: 14px;
  padding: 12px 0;
}
.tm__list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}
.tm__item {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #2a2a2a;
  border-radius: 6px;
  padding: 12px 14px;
}
.tm__item--best {
  outline: 1px solid rgba(30, 215, 96, 0.4);
}
.tm__item-main {
  flex: 1;
  min-width: 0;
}
.tm__item-title {
  font-weight: 700;
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.tm__badge {
  background: var(--accent);
  color: #000;
  font-size: 10px;
  font-weight: 800;
  border-radius: 999px;
  padding: 2px 8px;
  text-transform: uppercase;
}
.tm__item-desc {
  color: var(--text-subdued);
  font-size: 12px;
  margin-top: 4px;
}
.tm__like {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-subdued);
  font-size: 14px;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.06);
}
.tm__like:hover {
  color: #fff;
}
.tm__like.on {
  color: var(--accent);
}
.tm__use {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-subdued);
  font-size: 12px;
  font-weight: 700;
  padding: 6px 10px;
  border-radius: 999px;
  border: 1px solid var(--text-muted);
  white-space: nowrap;
}
.tm__use:hover {
  color: #fff;
  border-color: #fff;
}
.tm__use.on {
  color: var(--accent);
  border-color: var(--accent);
}
.tm__badge--mine {
  background: #4cb3ff;
}
.tm__del {
  color: var(--text-subdued);
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  flex: 0 0 28px;
}
.tm__del:hover {
  color: #f15e6c;
  background: rgba(255, 255, 255, 0.06);
}
.tm__create {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #fff;
  font-weight: 700;
  font-size: 14px;
  padding: 10px 0;
}
.tm__create:hover {
  color: var(--accent);
}
.tm__editor {
  margin-top: 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  padding-top: 16px;
}
.tm__modes {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}
.tm__modes button {
  font-size: 14px;
  font-weight: 700;
  color: var(--text-subdued);
  background: rgba(255, 255, 255, 0.07);
  border-radius: 999px;
  padding: 7px 16px;
}
.tm__modes button:hover {
  color: #fff;
}
.tm__modes button.on {
  background: #fff;
  color: #000;
}
.tm__chart {
  background: #181818;
  border-radius: 8px;
  padding: 12px 12px 8px;
  margin-bottom: 14px;
}
.tm__svg {
  display: block;
  width: 100%;
  height: auto;
}
.tm__gridline {
  stroke: rgba(255, 255, 255, 0.09);
  stroke-width: 1;
}
.tm__gridline--base {
  stroke: rgba(255, 255, 255, 0.22);
}
.tm__axis {
  fill: var(--text-subdued);
  font-size: 10px;
  font-family: var(--font);
}
.tm__line {
  fill: none;
  stroke-width: 2.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.tm__line--in {
  stroke: #1ed760;
}
.tm__line--out {
  stroke: #1ed760;
  opacity: 0.45;
}
.tm__area {
  stroke: none;
}
.tm__area--in {
  fill: rgba(30, 215, 96, 0.12);
}
.tm__area--out {
  fill: rgba(30, 215, 96, 0.05);
}
.tm__mini {
  width: 72px;
  height: 26px;
  flex: 0 0 72px;
  background: #181818;
  border-radius: 4px;
  padding: 1px;
}
.tm__mini .tm__line {
  stroke-width: 1.5;
}
.tm__legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  padding: 8px 4px 2px;
  font-size: 12px;
  color: var(--text-subdued);
}
.tm__legend span {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}
.tm__chip {
  width: 14px;
  height: 3px;
  border-radius: 2px;
  flex: 0 0 auto;
}
.tm__chip--in {
  background: #1ed760;
}
.tm__chip--out {
  background: rgba(30, 215, 96, 0.45);
}
.tm__simple {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
  color: var(--text-subdued);
}
.tm__simple-row {
  display: flex;
  align-items: center;
  gap: 14px;
}
.tm__simple-row input[type='range'] {
  flex: 1;
  accent-color: var(--accent);
}
.tm__simple-val {
  color: #fff;
  font-size: 16px;
  min-width: 38px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.tm__simple-hint {
  font-size: 12px;
  line-height: 1.45;
}
.tm__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.tm__grid label,
.tm__curve {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--text-subdued);
}
.tm__grid input,
.tm__curve select {
  background: #2a2a2a;
  border: 1px solid transparent;
  border-radius: 4px;
  color: #fff;
  padding: 10px 12px;
  font-size: 14px;
  outline: none;
}
.tm__grid input:focus,
.tm__curve select:focus {
  border-color: #fff;
}
.tm__curve {
  margin-top: 12px;
}
.tm__actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}
.tm__cancel {
  color: var(--text-subdued);
  font-weight: 700;
  font-size: 14px;
}
.tm__cancel:hover {
  color: #fff;
}
.tm__save {
  padding: 10px 24px;
  font-size: 14px;
}
.tm__editor-hint {
  font-size: 12px;
  margin-top: 10px;
}
</style>
