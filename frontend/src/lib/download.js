/** Скачивание аудиофайлов треков (кнопка «Скачать» на страницах коллекций). */
export async function downloadTracks(tracks, onProgress = () => {}) {
  const list = tracks.filter((t) => t.stream_url)
  let done = 0
  for (const t of list) {
    try {
      const res = await fetch(t.stream_url)
      const blob = await res.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      const artists = (t.artists || []).map((x) => x.name).join(', ')
      a.href = url
      a.download = `${artists ? artists + ' - ' : ''}${t.title}.mp3`
      document.body.appendChild(a)
      a.click()
      a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 10_000)
    } catch {
      /* один не скачался — едем дальше */
    }
    done++
    onProgress(done, list.length)
    await new Promise((r) => setTimeout(r, 400))
  }
  return done
}
