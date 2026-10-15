package ru.nepalimsya.sukify.data.offline

/**
 * Решения синхронизации загрузок — без файлов, сети и базы, чтобы их можно
 * было проверить тестами:
 *  - какие подборки выбраны (автокатегории ∪ вручную − исключения);
 *  - какие треки должны лежать на телефоне;
 *  - что докачать, что перекачать (сменилась ревизия файла) и что удалить
 *    (трек удалён в Sukify, убран из плейлиста, снят лайк, подборку больше
 *    не качаем).
 */
object DownloadPlanner {

    /** Аудио трека из манифеста. */
    data class Audio(val rev: String, val size: Long, val preview: Boolean)

    data class Diff(
        /** Новые строки и строки со сменившейся ревизией — в очередь. */
        val enqueue: List<DownloadEntity>,
        /** Больше не нужны — удалить файл и запись. */
        val remove: List<DownloadEntity>,
    ) {
        val isEmpty: Boolean get() = enqueue.isEmpty() && remove.isEmpty()
    }

    /** Ключи подборок, которые подпадают под автокатегории. */
    fun autoKeys(settings: DownloadSettings, library: ManifestLibrary): Set<String> = buildSet {
        if (settings.autoLiked) add(LIKED)
        if (settings.autoPlaylists) library.playlists.forEach { add("playlist:$it") }
        if (settings.autoAlbums) library.albums.forEach { add("release:$it") }
        if (settings.autoArtists) library.artists.forEach { add("artist:$it") }
    }

    /** Выбранные подборки: (авто ∪ вручную) − исключения, и только те, что есть в манифесте. */
    fun selectedKeys(settings: DownloadSettings, library: ManifestLibrary, available: Set<String>): Set<String> =
        ((autoKeys(settings, library) + settings.includes) - settings.excludes).filterTo(LinkedHashSet()) { it in available }

    /**
     * Треки, которые должны лежать на телефоне: из выбранных подборок, только
     * со звуком; 30-секундные превью — если их разрешено качать.
     */
    fun desired(
        selected: Set<String>,
        sourceTracks: Map<String, List<Long>>,
        audio: Map<Long, Audio>,
        previews: Boolean,
    ): Map<Long, Audio> {
        val out = LinkedHashMap<Long, Audio>()
        for (key in selected) {
            for (id in sourceTracks[key].orEmpty()) {
                if (id in out) continue
                val a = audio[id] ?: continue
                if (a.preview && !previews) continue
                out[id] = a
            }
        }
        return out
    }

    fun diff(desired: Map<Long, Audio>, current: List<DownloadEntity>, now: Long = System.currentTimeMillis()): Diff {
        val byId = current.associateBy { it.trackId }
        val enqueue = ArrayList<DownloadEntity>()
        var order = 0L
        for ((id, a) in desired) {
            val row = byId[id]
            when {
                row == null -> enqueue += DownloadEntity(
                    trackId = id,
                    rev = a.rev,
                    size = a.size,
                    state = DownloadState.QUEUED,
                    // Порядок очереди — порядок подборок (сначала то, что выбрали раньше).
                    queuedAt = now + order++,
                )
                row.rev != a.rev -> enqueue += row.copy(
                    // Файл заменили: старый играет, пока не докачан новый.
                    rev = a.rev,
                    size = a.size,
                    state = DownloadState.QUEUED,
                    bytes = 0,
                    error = null,
                    attempts = 0,
                    queuedAt = now + order++,
                )
            }
        }
        val remove = current.filter { it.trackId !in desired }
        return Diff(enqueue, remove)
    }

    const val LIKED = "liked"
}
