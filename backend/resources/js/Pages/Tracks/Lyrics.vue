<script setup>
import { Head, Link, router } from '@inertiajs/vue3'
import { ref, computed, watch, onUnmounted } from 'vue'

const props = defineProps({ track: Object, lyrics: Object })

const plain = ref(props.lyrics.plain || '')
const synced = ref(props.lyrics.synced || '')
const saving = ref(false)

// После сохранения Inertia присылает свежие пропсы (например, plain, собранный
// из LRC на сервере) — подхватываем их в поля.
watch(
  () => props.lyrics,
  (l) => {
    plain.value = l.plain || ''
    synced.value = l.synced || ''
  }
)

/** Разбор LRC: [mm:ss.xx] строка. Показывает и то, что не распозналось. */
const parsed = computed(() => {
  const out = []
  // row — номер строки в самом текстовом поле: по нему кнопка ⏱ ставит таймкод
  // именно туда, откуда строка пришла (пустые строки мы пропускаем).
  synced.value.split('\n').forEach((raw, row) => {
    const line = raw.trimEnd()
    if (!line.trim()) return
    const m = line.match(/^\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]\s?(.*)$/)
    if (!m) {
      out.push({ ok: false, raw: line, row })
      return
    }
    const ms =
      Number(m[1]) * 60000 + Number(m[2]) * 1000 + Number((m[3] || '0').padEnd(3, '0'))
    out.push({ ok: true, ms, text: m[4], row })
  })
  return out
})

const badLines = computed(() => parsed.value.filter((l) => !l.ok).length)
const outOfRange = computed(() => {
  const dur = props.track.duration_ms || 0
  return dur ? parsed.value.filter((l) => l.ok && l.ms > dur + 2000).length : 0
})
const unordered = computed(() => {
  let prev = -1
  let n = 0
  for (const l of parsed.value) {
    if (!l.ok) continue
    if (l.ms < prev) n++
    prev = l.ms
  }
  return n
})

// Предпросмотр: играем трек и подсвечиваем строку, которая должна звучать.
const audio = ref(null)
const positionMs = ref(0)
let raf = null
function onPlay() {
  cancelAnimationFrame(raf)
  const tick = () => {
    positionMs.value = (audio.value?.currentTime || 0) * 1000
    raf = requestAnimationFrame(tick)
  }
  raf = requestAnimationFrame(tick)
}
function onPause() {
  cancelAnimationFrame(raf)
}
onUnmounted(() => cancelAnimationFrame(raf))

const activeIndex = computed(() => {
  let idx = -1
  parsed.value.forEach((l, i) => {
    if (l.ok && l.ms <= positionMs.value) idx = i
  })
  return idx
})

function jumpTo(l) {
  if (!audio.value || !l.ok) return
  audio.value.currentTime = l.ms / 1000
  audio.value.play()
}

function fmt(ms) {
  const s = Math.max(0, Math.round(ms / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/** Вставить таймкод текущей позиции в начало выбранной строки. */
function stampLine(row) {
  const rows = synced.value.split('\n')
  if (rows[row] === undefined) return
  const clean = rows[row].replace(/^\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?\]\s?/, '')
  const ms = positionMs.value
  const mm = String(Math.floor(ms / 60000)).padStart(2, '0')
  const ss = String(Math.floor((ms % 60000) / 1000)).padStart(2, '0')
  const cs = String(Math.floor((ms % 1000) / 10)).padStart(2, '0')
  rows[row] = `[${mm}:${ss}.${cs}] ${clean}`
  synced.value = rows.join('\n')
}

function onFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    const text = String(reader.result || '')
    // .lrc — в поле синхронизированного, .txt — в обычный.
    if (/\[\d{1,3}:\d{2}/.test(text)) synced.value = text
    else plain.value = text
  }
  reader.readAsText(file)
  e.target.value = ''
}

function save() {
  saving.value = true
  router.put(
    `/admin/tracks/${props.track.id}/lyrics`,
    { plain: plain.value, synced: synced.value },
    { preserveScroll: true, onFinish: () => (saving.value = false) }
  )
}

function publish() {
  if (!confirm('Опубликовать этот текст на lrclib.net?')) return
  router.post(`/admin/tracks/${props.track.id}/lyrics/publish`, {}, { preserveScroll: true })
}

function flag() {
  const reason = prompt('Что не так с текстом на LRCLIB? (необязательно)')
  if (reason === null) return
  router.post(`/admin/tracks/${props.track.id}/lyrics/flag`, { reason }, { preserveScroll: true })
}
</script>

<template>
  <Head :title="`Текст: ${track.title}`" />

  <div class="max-w-6xl">
    <div class="mb-4">
      <Link :href="`/admin/releases/${track.release_id}`" class="text-neutral-400 hover:text-white text-sm">← {{ track.release || 'Релиз' }}</Link>
      <h1 class="text-2xl font-bold mt-1">Текст: {{ track.title }}</h1>
      <div class="text-neutral-400 text-sm">{{ track.artists.join(', ') }} · {{ fmt(track.duration_ms) }}</div>
    </div>

    <div class="mb-4 flex flex-wrap items-center gap-3 text-sm">
      <span
        class="px-2 py-1 rounded font-semibold"
        :class="lyrics.is_custom ? 'bg-green-900/50 text-green-300' : 'bg-neutral-800 text-neutral-300'"
      >
        {{ lyrics.is_custom ? 'Свой текст (главнее LRCLIB)' : 'Текст с LRCLIB' }}
      </span>
      <span v-if="lyrics.lrclib_id" class="text-neutral-400">запись LRCLIB #{{ lyrics.lrclib_id }}</span>
      <label class="cursor-pointer text-neutral-300 hover:text-white">
        <input type="file" accept=".lrc,.txt,text/plain" class="hidden" @change="onFile" />
        Загрузить .lrc / .txt
      </label>
    </div>

    <div class="grid gap-4 lg:grid-cols-2">
      <!-- Редактор -->
      <div class="space-y-4">
        <div>
          <label class="block text-xs font-bold mb-1 text-neutral-400">Синхронизированный текст (LRC)</label>
          <textarea
            v-model="synced"
            rows="16"
            spellcheck="false"
            placeholder="[00:12.34] Первая строка"
            class="w-full font-mono text-xs rounded-md bg-neutral-950 border border-neutral-700 px-3 py-2 outline-none focus:border-accent"
          ></textarea>
        </div>
        <div>
          <label class="block text-xs font-bold mb-1 text-neutral-400">
            Обычный текст (если пусто — соберём из LRC)
          </label>
          <textarea
            v-model="plain"
            rows="8"
            spellcheck="false"
            class="w-full text-xs rounded-md bg-neutral-950 border border-neutral-700 px-3 py-2 outline-none focus:border-accent"
          ></textarea>
        </div>

        <div class="flex flex-wrap gap-3">
          <button
            class="rounded-md bg-accent px-4 py-2 text-sm font-bold text-black disabled:opacity-50"
            :disabled="saving"
            @click="save"
          >
            {{ saving ? 'Сохраняем…' : 'Сохранить' }}
          </button>
          <button class="rounded-md border border-neutral-600 px-4 py-2 text-sm font-semibold hover:border-white" @click="publish">
            Опубликовать на LRCLIB
          </button>
          <button class="rounded-md border border-neutral-600 px-4 py-2 text-sm font-semibold hover:border-white" @click="flag">
            Пожаловаться на LRCLIB
          </button>
        </div>
      </div>

      <!-- Предпросмотр синхронизации -->
      <div>
        <div class="text-xs font-bold mb-1 text-neutral-400">Предпросмотр синхронизации</div>

        <audio
          v-if="track.stream_url"
          ref="audio"
          :src="track.stream_url"
          controls
          class="w-full mb-2"
          @play="onPlay"
          @pause="onPause"
          @seeked="positionMs = ($event.target.currentTime || 0) * 1000"
        ></audio>
        <p v-else class="text-neutral-500 text-xs mb-2">Аудио ещё не обработано — играть нечего.</p>

        <div class="mb-2 flex flex-wrap gap-3 text-xs">
          <span class="text-neutral-400">Строк: {{ parsed.length }}</span>
          <span :class="badLines ? 'text-red-400' : 'text-neutral-400'">без таймкода: {{ badLines }}</span>
          <span :class="unordered ? 'text-red-400' : 'text-neutral-400'">не по порядку: {{ unordered }}</span>
          <span :class="outOfRange ? 'text-red-400' : 'text-neutral-400'">за пределами трека: {{ outOfRange }}</span>
        </div>

        <div class="rounded-md border border-neutral-800 max-h-[540px] overflow-y-auto">
          <div
            v-for="(l, i) in parsed"
            :key="i"
            class="flex gap-3 px-3 py-1.5 text-sm border-b border-neutral-900 last:border-0"
            :class="[
              i === activeIndex ? 'bg-green-900/30 text-white' : 'text-neutral-300',
              !l.ok ? 'bg-red-900/20 text-red-300' : '',
            ]"
          >
            <button
              v-if="l.ok"
              class="tabular-nums text-neutral-500 hover:text-white w-12 text-left"
              title="Перемотать сюда"
              @click="jumpTo(l)"
            >
              {{ fmt(l.ms) }}
            </button>
            <span v-else class="w-12 text-xs">— </span>
            <span class="flex-1">{{ l.ok ? l.text || '♪' : l.raw }}</span>
            <button
              class="text-xs text-neutral-500 hover:text-white"
              title="Проставить текущее время плеера"
              @click="stampLine(l.row)"
            >
              ⏱
            </button>
          </div>
          <p v-if="!parsed.length" class="px-3 py-6 text-center text-neutral-500 text-sm">
            Вставь LRC слева — здесь появится разбор по строкам.
          </p>
        </div>
      </div>
    </div>
  </div>
</template>
