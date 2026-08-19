<script setup>
import { ref, watch, onMounted } from 'vue'
import api from '@/lib/api'
import { usePlayerStore } from '@/stores/player'
import { useUiStore } from '@/stores/ui'
import { useToastStore } from '@/stores/toasts'
import { useAuthStore } from '@/stores/auth'

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

// --- Профиль: имя, @handle, аватарка ---------------------------------------
const auth = useAuthStore()
const profileName = ref('')
const profileHandle = ref('')
const savingProfile = ref(false)
const avatarInput = ref(null)
const origin = location.origin

watch(
  () => auth.user,
  (u) => {
    profileName.value = u?.name || ''
    profileHandle.value = u?.username || ''
  },
  { immediate: true }
)

async function saveProfile() {
  savingProfile.value = true
  try {
    const { data } = await api.put('/me/profile', {
      name: profileName.value.trim(),
      username: profileHandle.value.trim(),
    })
    auth.user = data.data
    toasts.show('Профиль обновлён')
  } catch (e) {
    toasts.show(
      e?.response?.status === 422
        ? 'Такой @handle уже занят или содержит недопустимые символы'
        : 'Не удалось сохранить профиль'
    )
  } finally {
    savingProfile.value = false
  }
}

async function onAvatar(e) {
  const file = e.target.files?.[0]
  if (!file) return
  const form = new FormData()
  form.append('avatar', file)
  try {
    const { data } = await api.post('/me/avatar', form)
    auth.user = data.data
    toasts.show('Аватарка обновлена')
  } catch {
    toasts.show('Не подошёл файл — нужен JPEG, PNG или WebP до 8 МБ')
  }
  e.target.value = ''
}

async function removeAvatar() {
  const { data } = await api.delete('/me/avatar')
  auth.user = data.data
  toasts.show('Аватарка удалена')
}

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

    <section class="settings__group">
      <h2>Профиль</h2>

      <div class="setting">
        <div class="setting__text">
          <div class="setting__name">Аватарка</div>
          <div class="setting__desc">Видна в профиле, у друзей и в поиске</div>
        </div>
        <div class="settings__avarow">
          <div class="settings__ava">
            <img v-if="auth.user?.avatar_url" :src="auth.user.avatar_url" alt="" />
            <span v-else>{{ (auth.user?.name || '?')[0].toUpperCase() }}</span>
          </div>
          <button class="settings__btn" @click="avatarInput?.click()">Загрузить</button>
          <button v-if="auth.user?.avatar_url" class="settings__btn" @click="removeAvatar">Убрать</button>
          <input ref="avatarInput" type="file" accept="image/*" hidden @change="onAvatar" />
        </div>
      </div>

      <div class="setting">
        <div class="setting__text">
          <div class="setting__name">Отображаемое имя</div>
          <div class="setting__desc">Как тебя видят другие</div>
        </div>
        <input v-model="profileName" class="settings__input" maxlength="255" />
      </div>

      <div class="setting">
        <div class="setting__text">
          <div class="setting__name">Ссылка на профиль</div>
          <div class="setting__desc">{{ origin }}/user/{{ profileHandle || '…' }}</div>
        </div>
        <div class="settings__handle">
          <span>@</span>
          <input v-model="profileHandle" class="settings__input" maxlength="30" placeholder="handle" />
        </div>
      </div>

      <div class="settings__save">
        <button class="settings__btn settings__btn--primary" :disabled="savingProfile" @click="saveProfile">
          {{ savingProfile ? 'Сохраняем…' : 'Сохранить профиль' }}
        </button>
      </div>
    </section>

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
.settings__avarow {
  display: flex;
  align-items: center;
  gap: 12px;
}
.settings__ava {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: #333;
  display: grid;
  place-items: center;
  font-size: 24px;
  font-weight: 700;
  overflow: hidden;
  flex: 0 0 64px;
}
.settings__ava img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.settings__btn {
  border: 1px solid var(--text-muted);
  color: #fff;
  border-radius: 999px;
  padding: 8px 16px;
  font-size: 14px;
  font-weight: 600;
}
.settings__btn:hover:not(:disabled) {
  border-color: #fff;
}
.settings__btn--primary {
  background: #fff;
  color: #000;
  border-color: #fff;
}
.settings__btn:disabled {
  opacity: 0.6;
}
.settings__input {
  background: #2a2a2a;
  border: 1px solid transparent;
  border-radius: 4px;
  color: #fff;
  font-family: inherit;
  font-size: 14px;
  padding: 10px 12px;
  min-width: 220px;
}
.settings__input:focus {
  border-color: #727272;
  outline: none;
}
.settings__handle {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-subdued);
}
.settings__save {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
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
