// Загрузка архивов и папок в админке: архив уходит кусками и докачивается
// после обрыва, папка — по файлу. Без axios: нужен прогресс отправки (XHR).

function xsrfToken() {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return m ? decodeURIComponent(m[1]) : ''
}

/** Запрос с JSON-ответом и прогрессом отправки. Сетевой сбой — reject. */
export function request(method, url, body = null, { headers = {}, onProgress, signal } = {}) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open(method, url)
    xhr.setRequestHeader('Accept', 'application/json')
    xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest')
    xhr.setRequestHeader('X-XSRF-TOKEN', xsrfToken())
    for (const [k, v] of Object.entries(headers)) xhr.setRequestHeader(k, v)

    let payload = body
    if (body && !(body instanceof Blob) && !(body instanceof FormData)) {
      xhr.setRequestHeader('Content-Type', 'application/json')
      payload = JSON.stringify(body)
    }
    if (onProgress) xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded)

    xhr.onload = () => {
      let data = null
      try {
        data = JSON.parse(xhr.responseText)
      } catch {}
      resolve({ status: xhr.status, data })
    }
    xhr.onerror = () => reject(new Error('network'))
    xhr.ontimeout = () => reject(new Error('timeout'))
    if (signal) {
      if (signal.aborted) return reject(new DOMException('aborted', 'AbortError'))
      signal.addEventListener('abort', () => {
        xhr.abort()
        reject(new DOMException('aborted', 'AbortError'))
      })
    }
    xhr.send(payload)
  })
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** Повторяет сетевые сбои и 5xx с нарастающей паузой; ошибки 4xx отдаёт сразу. */
async function withRetry(fn, { signal, onRetry } = {}) {
  for (let attempt = 0; ; attempt++) {
    try {
      const res = await fn()
      if (res.status < 500 || attempt >= 6) return res
    } catch (e) {
      if (e.name === 'AbortError' || attempt >= 6) throw e
    }
    if (signal?.aborted) throw new DOMException('aborted', 'AbortError')
    onRetry?.(attempt + 1)
    await sleep(Math.min(30000, 1000 * 2 ** attempt))
  }
}

export function messageOf(res, fallback = 'Something went wrong') {
  return res?.data?.message || fallback
}

/** Начать загрузку или найти недокачанную (тот же файл — то же имя и размер). */
export async function start({ name, size, kind, files = 0, options }) {
  const res = await request('POST', '/admin/uploads', { name, size, kind, files, options })
  if (res.status !== 200 && res.status !== 201) throw new Error(messageOf(res, 'Could not start the upload'))
  return res.data
}

/** Архив кусками с того места, где сервер его уже держит. */
export async function sendArchive(file, state, { onProgress, onRetry, signal } = {}) {
  let offset = state.received_bytes || 0
  const chunk = state.chunk_bytes || 32 * 1024 * 1024
  onProgress?.(offset)

  while (offset < file.size) {
    const end = Math.min(offset + chunk, file.size)
    const res = await withRetry(
      () =>
        request('POST', `/admin/uploads/${state.id}/chunk`, file.slice(offset, end), {
          headers: { 'Content-Type': 'application/octet-stream', 'X-Upload-Offset': String(offset) },
          onProgress: (loaded) => onProgress?.(offset + loaded),
          signal,
        }),
      { signal, onRetry },
    )

    if (res.status === 409 && typeof res.data?.received_bytes === 'number') {
      // Сервер держит другое количество — продолжаем от его.
      if (res.data.received_bytes === offset) throw new Error(messageOf(res))
      offset = res.data.received_bytes
      continue
    }
    if (res.status !== 200) throw new Error(messageOf(res, `Upload failed (${res.status})`))

    offset = res.data.received_bytes
    onProgress?.(offset)
  }
}

/** Папка: файлы по одному (три параллельно), пропуская уже полученные. */
export async function sendFolder(entries, state, { onProgress, onRetry, onSkip, signal } = {}) {
  const have = new Set(state.received_paths || [])
  let sent = entries.filter((e) => have.has(e.path)).reduce((n, e) => n + e.file.size, 0)
  const queue = entries.filter((e) => !have.has(e.path))
  const inFlight = new Map()
  const report = () => onProgress?.(sent + [...inFlight.values()].reduce((a, b) => a + b, 0))
  report()

  async function worker() {
    while (queue.length) {
      if (signal?.aborted) throw new DOMException('aborted', 'AbortError')
      const entry = queue.shift()
      const form = new FormData()
      form.append('path', entry.path)
      form.append('file', entry.file)
      const res = await withRetry(
        () =>
          request('POST', `/admin/uploads/${state.id}/files`, form, {
            onProgress: (loaded) => {
              inFlight.set(entry.path, loaded)
              report()
            },
            signal,
          }),
        { signal, onRetry },
      )
      inFlight.delete(entry.path)
      if (res.status === 422) onSkip?.(entry.path, messageOf(res))
      else if (res.status !== 200) throw new Error(messageOf(res, `Upload failed (${res.status})`))
      sent += entry.file.size
      report()
    }
  }

  await Promise.all([worker(), worker(), worker()])

  const done = await request('POST', `/admin/uploads/${state.id}/finish`)
  if (done.status !== 200) throw new Error(messageOf(done, 'Could not finish the upload'))
}

/** Перетащенное: один архив или файлы и папки (обходим вглубь). */
export async function collectDropped(dataTransfer) {
  const items = [...(dataTransfer.items || [])]
  const roots = items.map((i) => i.webkitGetAsEntry?.()).filter(Boolean)
  if (!roots.length) return [...dataTransfer.files].map((file) => ({ file, path: file.name }))

  const out = []
  async function walk(entry, prefix) {
    if (entry.isFile) {
      const file = await new Promise((res, rej) => entry.file(res, rej))
      out.push({ file, path: prefix + file.name })
      return
    }
    const reader = entry.createReader()
    // readEntries отдаёт порциями по ~100 — читаем, пока не вернёт пусто.
    for (;;) {
      const batch = await new Promise((res, rej) => reader.readEntries(res, rej))
      if (!batch.length) break
      for (const child of batch) await walk(child, `${prefix}${entry.name}/`)
    }
  }
  for (const root of roots) await walk(root, '')
  return out
}

/** Из <input webkitdirectory>: пути относительно выбранной папки. */
export function collectInput(fileList) {
  return [...fileList].map((file) => ({ file, path: file.webkitRelativePath || file.name }))
}

export function extOf(name) {
  const i = name.lastIndexOf('.')
  return i >= 0 ? name.slice(i + 1).toLowerCase() : ''
}

export function formatBytes(bytes) {
  if (!bytes) return '0 MB'
  const gb = bytes / 1024 ** 3
  if (gb >= 1) return `${gb.toFixed(gb >= 10 ? 0 : 1)} GB`
  return `${Math.max(1, Math.round(bytes / 1024 ** 2))} MB`
}

export function formatDuration(ms) {
  if (!ms) return '—'
  const s = Math.round(ms / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}
