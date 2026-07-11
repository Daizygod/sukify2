<script setup>
import { ref, onMounted } from 'vue'
import api from '@/lib/api'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'
import { useToastStore } from '@/stores/toasts'

const player = usePlayerStore()
const ui = useUiStore()
const toasts = useToastStore()

const crossfade = ref(0)
const normalize = ref(true)
const smartShuffle = ref(false)
const loaded = ref(false)

onMounted(async () => {
  try {
    const { data } = await api.get('/playback-settings')
    crossfade.value = data.data.default_crossfade_seconds ?? 0
    normalize.value = (data.data.target_loudness_lufs ?? -14) > -29
    smartShuffle.value = !!data.data.smart_shuffle_enabled
  } finally {
    loaded.value = true
  }
})

let saveTimer
function save() {
  clearTimeout(saveTimer)
  saveTimer = setTimeout(async () => {
    await api.put('/playback-settings', {
      default_crossfade_seconds: crossfade.value,
      // «выключенная» нормализация = очень тихий таргет, гейн стремится к 1
      target_loudness_lufs: normalize.value ? -14 : -29.9,
      smart_shuffle_enabled: smartShuffle.value,
    })
    player.defaultCrossfadeSeconds = crossfade.value
    player.targetLufs = normalize.value ? -14 : -29.9
    toasts.show('Настройки сохранены')
  }, 400)
}
</script>

<template>
  <div class="content-pad settings">
    <h1 class="settings__title">Настройки</h1>

    <section v-if="loaded" class="settings__group">
      <h2>Воспроизведение</h2>

      <div class="setting">
        <div class="setting__text">
          <div class="setting__name">Кроссфейд</div>
          <div class="setting__desc">Плавный переход между треками (если у пары нет своего перехода сообщества)</div>
        </div>
        <div class="setting__ctl setting__ctl--slider">
          <span class="muted">0 c</span>
          <input v-model.number="crossfade" type="range" min="0" max="12" step="1" @change="save" />
          <span class="settings__val">{{ crossfade }} c</span>
        </div>
      </div>

      <div class="setting">
        <div class="setting__text">
          <div class="setting__name">Нормализация громкости</div>
          <div class="setting__desc">Выравнивает громкость треков по LUFS, чтобы не дёргать ручку громкости</div>
        </div>
        <label class="toggle">
          <input v-model="normalize" type="checkbox" @change="save" />
          <span class="toggle__track"></span>
        </label>
      </div>

      <div class="setting">
        <div class="setting__text">
          <div class="setting__name">Умное перемешивание</div>
          <div class="setting__desc">Шафл учитывает похожесть треков, а не просто случайный порядок</div>
        </div>
        <label class="toggle">
          <input v-model="smartShuffle" type="checkbox" @change="save" />
          <span class="toggle__track"></span>
        </label>
      </div>
    </section>

    <section class="settings__group">
      <h2>Интерфейс</h2>
      <div class="setting">
        <div class="setting__text">
          <div class="setting__name">Компактные списки треков</div>
          <div class="setting__desc">Плотные строки без обложек в таблицах</div>
        </div>
        <label class="toggle">
          <input :checked="ui.listCompact" type="checkbox" @change="ui.toggleListCompact()" />
          <span class="toggle__track"></span>
        </label>
      </div>
      <div class="setting">
        <div class="setting__text">
          <div class="setting__name">Язык</div>
          <div class="setting__desc">Русский</div>
        </div>
      </div>
    </section>

    <section class="settings__group">
      <h2>Горячие клавиши</h2>
      <div class="settings__keys muted">
        <div><kbd>Пробел</kbd> — пауза/играть</div>
        <div><kbd>←</kbd> / <kbd>→</kbd> — перемотка ±5 сек</div>
        <div><kbd>Ctrl</kbd>+<kbd>←</kbd> / <kbd>→</kbd> — предыдущий/следующий трек</div>
        <div><kbd>M</kbd> — выключить звук</div>
        <div><kbd>F</kbd> — полноэкранный режим</div>
        <div><kbd>T</kbd> — текст песни</div>
        <div><kbd>Ctrl</kbd>+<kbd>K</kbd> — поиск</div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.settings__title {
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin-bottom: 24px;
}
.settings__group {
  max-width: 720px;
  margin-bottom: 32px;
}
.settings__group h2 {
  font-size: 18px;
  font-weight: 700;
  margin-bottom: 12px;
}
.setting {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 14px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.setting__name {
  font-size: 16px;
  font-weight: 600;
}
.setting__desc {
  color: var(--text-subdued);
  font-size: 14px;
  margin-top: 4px;
}
.setting__ctl--slider {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
}
.settings__val {
  min-width: 34px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
input[type='range'] {
  accent-color: var(--accent);
  width: 160px;
}
.toggle {
  position: relative;
  display: inline-block;
  flex: 0 0 auto;
}
.toggle input {
  display: none;
}
.toggle__track {
  display: block;
  width: 42px;
  height: 24px;
  background: #4d4d4d;
  border-radius: 999px;
  position: relative;
  transition: background 0.15s ease;
  cursor: pointer;
}
.toggle__track::after {
  content: '';
  position: absolute;
  top: 3px;
  left: 3px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  transition: translate 0.15s ease;
}
.toggle input:checked + .toggle__track {
  background: var(--accent);
}
.toggle input:checked + .toggle__track::after {
  translate: 18px 0;
}
.settings__keys {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 14px;
}
kbd {
  background: #2a2a2a;
  border-radius: 4px;
  padding: 2px 8px;
  font-family: inherit;
  font-size: 12px;
  color: #fff;
}
</style>
