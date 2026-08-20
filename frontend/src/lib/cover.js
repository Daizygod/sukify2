/**
 * Ссылка на обложку не меньше заданного размера (или самая большая, что есть).
 *
 * Нужна там, где картинку нельзя отдавать на откуп srcset: в каруселях мини- и
 * полноэкранного плеера соседние слайды обязаны брать ровно тот же файл, что и
 * центральный. Иначе после свайпа в центре оказывается другая ссылка, браузер
 * идёт за ней заново, и кадр-другой видно две обложки сразу.
 */
export function coverUrl(cover, minSize = 640) {
  if (!cover) return null
  const arr = Array.isArray(cover)
    ? cover
    : Object.entries(cover).map(([size, url]) => ({ size: Number(size), url }))
  const sorted = arr
    .filter((x) => x && x.url && Number(x.size) > 0)
    .map((x) => ({ size: Number(x.size), url: x.url }))
    .sort((a, b) => a.size - b.size)
  if (!sorted.length) return null
  return (sorted.find((x) => x.size >= minSize) || sorted[sorted.length - 1]).url
}
