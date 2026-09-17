import { invoke } from '@tauri-apps/api/core'
import { Centrifuge } from 'centrifuge'

/**
 * Компаньон Sukify: слушает тот же канал Centrifugo, что и вкладки браузера
 * («Sukify Connect»), и зеркалит состояние плеера в Discord через IPC.
 *
 * Сам он в списке устройств не появляется: hello/ping не публикует, командами
 * не управляет — только читает. Иначе в пульте висело бы устройство, на которое
 * нельзя перевести воспроизведение.
 */

const DEFAULT_SERVER = 'http://localhost:8088'
const HEARTBEAT_MS = 60_000
const STATUS_POLL_MS = 2_000

let config = { serverUrl: DEFAULT_SERVER, frontendUrl: '', token: '', deviceId: null }

// Настройки трансляции приезжают с сервера: тумблеры в вебе должны доезжать
// сюда без перезапуска приложения.
let settings = {
  presence_enabled: true,
  show_when_paused: false,
  show_button: true,
  show_cover: true,
}

let centrifuge = null
let lastState = null
let discordStatus = { connected: false, muted: false, user: null, error: null }
let pairing = null // { userCode, verificationUri } пока идёт привязка
let fatal = ''

const app = document.getElementById('app')

// --- Сеть -------------------------------------------------------------------

async function request(path, { method = 'GET', body, auth = true, base } = {}) {
  const headers = { Accept: 'application/json' }
  if (body) headers['Content-Type'] = 'application/json'
  if (auth && config.token) headers.Authorization = `Bearer ${config.token}`

  const response = await fetch(`${base || config.serverUrl}/api${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  })

  const data = await response.json().catch(() => null)
  if (!response.ok) {
    const error = new Error(data?.error || data?.message || `HTTP ${response.status}`)
    error.status = response.status
    error.payload = data
    throw error
  }

  return data
}

// --- Привязка (device code flow) --------------------------------------------

function detectPlatform() {
  const ua = navigator.userAgent
  if (ua.includes('Windows')) return 'Windows'
  if (ua.includes('Mac')) return 'macOS'
  if (ua.includes('Linux')) return 'Linux'
  return 'компьютер'
}

async function startPairing(serverUrl) {
  fatal = ''
  const base = serverUrl.replace(/\/+$/, '')

  let info
  try {
    info = await request('/desktop/device-code', {
      base,
      auth: false,
      method: 'POST',
      body: { name: `Sukify Desktop (${detectPlatform()})`, platform: detectPlatform() },
    })
  } catch (e) {
    fatal = `Не достучались до сервера: ${e.message}`
    render()
    return
  }

  config.serverUrl = base
  // Адрес SPA нужен для кнопки «Слушать в Sukify» под статусом.
  config.frontendUrl = info.verification_uri.replace(/\/link-device$/, '')

  pairing = { userCode: info.user_code, verificationUri: info.verification_uri_complete }
  render()

  await invoke('open_url', { url: info.verification_uri_complete }).catch(() => {})
  await pollForToken(base, info)
}

async function pollForToken(base, info) {
  const deadline = Date.now() + info.expires_in * 1000

  while (Date.now() < deadline && pairing) {
    await sleep(info.interval * 1000)

    try {
      const granted = await request('/desktop/token', {
        base,
        auth: false,
        method: 'POST',
        body: { device_code: info.device_code },
      })

      config.token = granted.token
      config.deviceId = granted.device_id
      await invoke('config_save', { value: config })

      pairing = null
      render()
      await start()
      return
    } catch (e) {
      // Пользователь ещё не подтвердил — это штатное состояние, ждём дальше.
      if (e.payload?.error === 'authorization_pending') continue

      pairing = null
      fatal =
        e.payload?.error === 'expired_token'
          ? 'Код истёк — запроси новый.'
          : `Привязка не удалась: ${e.message}`
      render()
      return
    }
  }

  if (pairing) {
    pairing = null
    fatal = 'Код истёк — запроси новый.'
    render()
  }
}

async function unpair() {
  config = { serverUrl: config.serverUrl, frontendUrl: '', token: '', deviceId: null }
  await invoke('config_save', { value: config })

  centrifuge?.disconnect()
  centrifuge = null
  lastState = null
  await invoke('set_now_playing', { now: null })

  render()
}

// --- Поток состояния плеера --------------------------------------------------

async function connectRealtime() {
  const realtime = await request('/realtime/connection-token', { method: 'POST' })

  centrifuge = new Centrifuge(realtime.ws_url, {
    token: realtime.token,
    getToken: async () => (await request('/realtime/connection-token', { method: 'POST' })).token,
  })

  const subscription = centrifuge.newSubscription(realtime.playback_channel)
  subscription.on('publication', (ctx) => handleMessage(ctx.data))
  subscription.subscribe()

  centrifuge.on('connected', render)
  centrifuge.on('disconnected', render)
  centrifuge.connect()
}

function handleMessage(message) {
  if (!message?.t) return

  if (message.t === 'bye') {
    lastState = null
    push()
    render()
    return
  }

  if (message.t !== 'state') return

  // Namespace `user` включает recovery: при подписке Centrifugo доливает до
  // десяти сообщений из истории. Протухшие оттуда не показываем — иначе на
  // старте мелькнёт трек, который слушали полчаса назад.
  if (message.ts && Date.now() - message.ts > 60_000) return

  lastState = message
  push()
  render()
}

/** Собрать текущее состояние и отдать его в Rust (тот сам решит, когда слать). */
function push() {
  const track = lastState?.track

  if (!track || !settings.presence_enabled) return clear()
  if (!lastState.playing && !settings.show_when_paused) return clear()

  invoke('set_now_playing', {
    now: {
      playing: !!lastState.playing,
      title: track.title || '',
      artists: track.artists || '',
      album: track.album || null,
      coverUrl: track.coverBig || track.cover || null,
      trackUrl:
        track.releaseSlug && config.frontendUrl
          ? `${config.frontendUrl}/release/${track.releaseSlug}`
          : null,
      positionMs: Math.round(lastState.pos || 0),
      durationMs: Math.round(lastState.dur || track.duration_ms || 0),
      showButton: !!settings.show_button,
      showCover: !!settings.show_cover,
    },
  }).catch(() => {})
}

function clear() {
  invoke('set_now_playing', { now: null }).catch(() => {})
}

// --- Пульс и статус ----------------------------------------------------------

async function heartbeat() {
  if (!config.token) return

  try {
    const { data } = await request('/desktop/heartbeat', {
      method: 'POST',
      body: { device_id: config.deviceId, discord_id: discordStatus.user?.id || null },
    })

    // Сравниваем только тумблеры: matches сюда не входит, иначе «изменилось»
    // срабатывало бы на каждом пульсе.
    const next = pick(data)
    const changed = JSON.stringify(pick(settings)) !== JSON.stringify(next)

    settings = { ...next, matches: data.matches_linked_account }
    if (changed) push()
  } catch (e) {
    // 401 — токен отозвали в настройках: честно разлогиниваемся.
    if (e.status === 401) await unpair()
  }
}

function pick(data) {
  return {
    presence_enabled: data.presence_enabled,
    show_when_paused: data.show_when_paused,
    show_button: data.show_button,
    show_cover: data.show_cover,
  }
}

async function pollStatus() {
  const next = await invoke('get_status').catch(() => null)
  if (!next) return

  if (JSON.stringify(next) !== JSON.stringify(discordStatus)) {
    discordStatus = next
    render()
  }
}

// --- Отрисовка ---------------------------------------------------------------

function render() {
  app.innerHTML = config.token ? mainScreen() : setupScreen()
  wire()
}

function setupScreen() {
  if (pairing) {
    return `
      <section class="card">
        <div class="logo">🎧</div>
        <h1>Подтверди в браузере</h1>
        <p class="lead">Мы открыли страницу Sukify. Проверь, что там тот же код:</p>
        <div class="code">${escapeHtml(pairing.userCode)}</div>
        <p class="hint">Ждём подтверждения…</p>
        <button class="btn" data-act="cancel">Отмена</button>
      </section>`
  }

  return `
    <section class="card">
      <div class="logo">🎧</div>
      <h1>Sukify Desktop</h1>
      <p class="lead">
        Показывает в Discord, что ты слушаешь. Укажи адрес сервера Sukify и подключи аккаунт.
      </p>
      ${fatal ? `<p class="error">${escapeHtml(fatal)}</p>` : ''}
      <input class="input" id="server" value="${escapeHtml(config.serverUrl)}" spellcheck="false" />
      <button class="btn btn--primary" data-act="pair">Подключить аккаунт</button>
    </section>`
}

function mainScreen() {
  const track = lastState?.track
  const online = centrifuge?.state === 'connected'

  const discordLine = discordStatus.connected
    ? `Discord: ${escapeHtml(discordStatus.user?.displayName || 'подключён')}`
    : `Discord: ${escapeHtml(discordStatus.error || 'ищем запущенный клиент…')}`

  const localCover =
    track?.coverBig && /^https?:\/\/(localhost|127\.0\.0\.1)/.test(track.coverBig)

  return `
    <section class="card card--wide">
      <div class="rows">
        <div class="row ${discordStatus.connected ? 'row--ok' : 'row--warn'}">
          <span class="dot"></span>${discordLine}
        </div>
        <div class="row ${online ? 'row--ok' : 'row--warn'}">
          <span class="dot"></span>Sukify: ${online ? 'на связи' : 'подключаемся…'}
        </div>
        ${
          discordStatus.muted
            ? '<div class="row row--warn"><span class="dot"></span>Трансляция выключена в трее</div>'
            : ''
        }
        ${
          settings.presence_enabled
            ? ''
            : '<div class="row row--warn"><span class="dot"></span>Трансляция выключена в настройках Sukify</div>'
        }
        ${
          settings.matches === false
            ? '<div class="row row--warn"><span class="dot"></span>Discord запущен под другим аккаунтом, чем привязан в Sukify</div>'
            : ''
        }
      </div>

      ${
        track
          ? `<div class="now">
               ${track.cover ? `<img class="now__art" src="${escapeHtml(track.cover)}" alt="" />` : ''}
               <div class="now__text">
                 <div class="now__title">${escapeHtml(track.title || '')}</div>
                 <div class="now__artist">${escapeHtml(track.artists || '')}</div>
                 <div class="now__state">${lastState.playing ? 'играет' : 'на паузе'}</div>
               </div>
             </div>`
          : '<p class="lead">Ничего не играет. Включи трек в Sukify — статус появится сам.</p>'
      }

      ${
        localCover
          ? `<p class="hint hint--warn">
               Обложки отдаются с localhost — Discord их не скачает. В статусе будет текст без картинки.
             </p>`
          : ''
      }

      <label class="check">
        <input type="checkbox" id="autostart" />
        Запускать вместе с системой
      </label>

      <button class="btn" data-act="unpair">Отключить аккаунт</button>
    </section>`
}

function wire() {
  app.querySelector('[data-act="pair"]')?.addEventListener('click', () => {
    startPairing(app.querySelector('#server').value.trim() || DEFAULT_SERVER)
  })

  app.querySelector('[data-act="cancel"]')?.addEventListener('click', () => {
    pairing = null
    render()
  })

  app.querySelector('[data-act="unpair"]')?.addEventListener('click', unpair)

  const autostart = app.querySelector('#autostart')
  if (autostart) {
    autostartApi()
      .then(async (api) => {
        autostart.checked = await api.isEnabled()
        autostart.addEventListener('change', async () => {
          if (autostart.checked) await api.enable()
          else await api.disable()
        })
      })
      .catch(() => autostart.closest('.check')?.remove())
  }
}

// Плагин автозапуска грузим лениво: без него приложение всё равно работает.
function autostartApi() {
  return import('@tauri-apps/plugin-autostart')
}

// --- Утилиты -----------------------------------------------------------------

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function escapeHtml(value) {
  return String(value).replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]
  )
}

// --- Запуск ------------------------------------------------------------------

async function start() {
  if (!config.token) return

  try {
    await heartbeat()
    await connectRealtime()
  } catch (e) {
    if (e.status === 401) {
      await unpair()
      return
    }
    fatal = `Сервер недоступен: ${e.message}`
  }

  render()
}

async function boot() {
  const stored = await invoke('config_load').catch(() => null)
  if (stored && typeof stored === 'object') config = { ...config, ...stored }

  render()
  await start()

  setInterval(heartbeat, HEARTBEAT_MS)
  setInterval(pollStatus, STATUS_POLL_MS)
}

boot()
