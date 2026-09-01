import { computed, unref } from 'vue'
import { usePlayerStore } from '@/stores/player'
import { useDeviceStore } from '@/stores/devices'

/**
 * Единая логика кнопок «играть подборку» (альбом, плейлист, микс, любимое…).
 *
 * Раньше каждая страница решала сама, играет ли «её» подборка, — и половина
 * кнопок оставалась вечным ▶ или вовсе не реагировала. Здесь одно правило:
 * подборка опознаётся по стабильному ключу (`release:slug`, `liked`, …),
 * который плеер хранит в contextKey и рассылает пультам Connect.
 *
 * @param {Object}   opts
 * @param {String|Function} opts.key    ключ контекста (или геттер)
 * @param {Array|Function}  opts.tracks треки или (async) загрузчик треков
 * @param {String|Function} opts.name   имя контекста для очереди
 */
export function usePlayContext(opts) {
  const player = usePlayerStore()
  const devices = useDeviceStore()

  const key = computed(() => unref(typeof opts.key === 'function' ? opts.key() : opts.key) || '')

  /** Играет (или стоит на паузе) именно эта подборка — включая пульт Connect. */
  const isThisContext = computed(() => {
    if (!key.value) return false
    return devices.isRemote
      ? devices.remoteState?.ctxKey === key.value
      : player.contextKey === key.value
  })

  const isPlaying = computed(
    () =>
      isThisContext.value &&
      (devices.isRemote ? !!devices.remoteState?.playing : player.isPlaying)
  )

  /** ▶ — включить/продолжить, ⏸ — поставить на паузу. */
  async function toggle(e) {
    // Второй клик даблклика: иначе подборка включается и тут же встаёт.
    if (e && e.detail > 1) return
    if (isThisContext.value) return player.togglePlay()

    const tracks = typeof opts.tracks === 'function' ? await opts.tracks() : unref(opts.tracks)
    if (!tracks?.length) return
    // Имя приходит геттером, как ключ и треки: без вызова в очередь уезжала
    // сама функция, и панель писала «Далее из: () => props.name».
    const name = typeof opts.name === 'function' ? opts.name() : opts.name
    player.playContext(tracks, 0, { name: unref(name) || '', key: key.value })
  }

  return { isThisContext, isPlaying, toggle }
}
