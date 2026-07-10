import { watch } from 'vue'

let pipWindow = null
let stopWatch = null

/**
 * Мини-плеер: маленькое always-on-top окно (Document Picture-in-Picture,
 * Chrome/Edge 116+). Рисуем простой DOM и синхронизируем его со стором.
 * Возвращает false, если API недоступен.
 */
export async function openMiniplayer(player) {
  if (!('documentPictureInPicture' in window)) return false
  if (pipWindow && !pipWindow.closed) {
    pipWindow.focus()
    return true
  }

  pipWindow = await window.documentPictureInPicture.requestWindow({
    width: 320,
    height: 96,
  })

  const doc = pipWindow.document
  doc.head.innerHTML = `<style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      font-family: 'Manrope', -apple-system, 'Segoe UI', sans-serif;
      background: #121212; color: #fff; height: 100vh;
      display: flex; align-items: center; gap: 12px; padding: 10px 14px;
      overflow: hidden; user-select: none;
    }
    img { width: 56px; height: 56px; border-radius: 4px; object-fit: cover; background: #333; }
    .meta { flex: 1; min-width: 0; }
    .title { font-size: 13px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .artists { font-size: 11px; color: #b3b3b3; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 2px; }
    .controls { display: flex; align-items: center; gap: 10px; }
    button { background: none; border: none; color: #b3b3b3; cursor: pointer; font-size: 16px; line-height: 1; padding: 4px; }
    button:hover { color: #fff; }
    .play {
      width: 32px; height: 32px; border-radius: 50%; background: #fff; color: #000;
      display: grid; place-items: center; font-size: 13px;
    }
    .bar { position: absolute; left: 0; right: 0; bottom: 0; height: 3px; background: #333; }
    .bar i { display: block; height: 100%; background: #1ed760; width: 0%; }
  </style>`

  doc.body.innerHTML = `
    <img id="cover" alt="" />
    <div class="meta">
      <div class="title" id="title"></div>
      <div class="artists" id="artists"></div>
    </div>
    <div class="controls">
      <button id="prev" title="Назад">⏮</button>
      <button id="play" class="play" title="Пауза/играть">▶</button>
      <button id="next" title="Далее">⏭</button>
    </div>
    <div class="bar"><i id="prog"></i></div>
  `

  const el = (id) => doc.getElementById(id)
  el('play').onclick = () => player.togglePlay()
  el('prev').onclick = () => player.prev()
  el('next').onclick = () => player.next()

  const coverUrl = (t) => {
    if (!t?.cover) return ''
    if (Array.isArray(t.cover)) return t.cover.find((c) => c.size >= 64)?.url || t.cover[0]?.url || ''
    return t.cover[64] || t.cover[300] || ''
  }

  const sync = () => {
    const t = player.currentTrack
    el('title').textContent = t?.title || 'Ничего не играет'
    el('artists').textContent = (t?.artists || []).map((a) => a.name).join(', ')
    el('cover').src = coverUrl(t)
    el('play').textContent = player.isPlaying ? '❚❚' : '▶'
    el('prog').style.width = `${(player.progress * 100).toFixed(1)}%`
  }
  sync()

  stopWatch = watch(
    () => [player.currentTrack?.id, player.isPlaying, Math.round(player.progress * 200)],
    sync
  )

  pipWindow.addEventListener('pagehide', () => {
    stopWatch?.()
    stopWatch = null
    pipWindow = null
  })

  return true
}
