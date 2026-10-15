package ru.nepalimsya.sukify.data.offline

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.nepalimsya.sukify.di.ApplicationScope
import ru.nepalimsya.sukify.di.IoDispatcher
import ru.nepalimsya.sukify.ui.components.Toasts

/** Действия с загрузками: ⬇ на подборках, галочки и переключатели в настройках. */
@Singleton
class Downloads @Inject constructor(
    private val rules: DownloadRules,
    private val sync: OfflineSync,
    private val state: OfflineState,
    private val storage: OfflineStorage,
    private val dao: DownloadDao,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val moveLock = Mutex()

    fun isActive(key: String): Boolean = state.collections.value[key]?.active == true

    /** ⬇ на подборке: скачать или убрать из загрузок. */
    fun toggle(key: String, title: String? = null) {
        if (isActive(key)) remove(key, title) else add(key, title)
    }

    fun add(key: String, title: String? = null) {
        scope.launch {
            rules.include(key)
            sync.rulesChanged()
            val settings = rules.current()
            Toasts.show(
                buildString {
                    append(if (title != null) "«$title» будет скачан" else "Скачиваю")
                    if (!settings.cellular) append(" — по Wi-Fi")
                },
            )
        }
    }

    fun remove(key: String, title: String? = null) {
        scope.launch {
            rules.exclude(key, coveredByAuto = key in sync.auto.value)
            sync.rulesChanged()
            Toasts.show(if (title != null) "«$title» удалён из загрузок" else "Удалено из загрузок")
        }
    }

    fun setAuto(liked: Boolean? = null, albums: Boolean? = null, playlists: Boolean? = null, artists: Boolean? = null) {
        scope.launch {
            rules.update {
                it.copy(
                    autoLiked = liked ?: it.autoLiked,
                    autoAlbums = albums ?: it.autoAlbums,
                    autoPlaylists = playlists ?: it.autoPlaylists,
                    autoArtists = artists ?: it.autoArtists,
                    // Включили категорию целиком — старые исключения из неё больше не нужны.
                    excludes = if (listOfNotNull(liked, albums, playlists, artists).any { v -> v }) emptySet() else it.excludes,
                )
            }
            sync.rulesChanged()
        }
    }

    /** Мобильная сеть: меняем условие у уже стоящей в очереди работы. */
    fun setCellular(allowed: Boolean) {
        scope.launch {
            rules.update { it.copy(cellular = allowed) }
            sync.scheduleDownloads(rules.current(), replace = true)
        }
    }

    fun setPreviews(allowed: Boolean) {
        scope.launch {
            rules.update { it.copy(previews = allowed) }
            sync.rulesChanged()
        }
    }

    fun setOfflineMode(enabled: Boolean) {
        scope.launch { rules.update { it.copy(offlineMode = enabled) } }
    }

    /** Телефон ↔ карта памяти: переносим уже скачанное в фоне. */
    fun setVolume(volume: StorageVolume) {
        scope.launch(io) {
            moveLock.withLock {
                if (volume == StorageVolume.SdCard && !storage.sdCardAvailable) {
                    Toasts.show("Карта памяти не найдена")
                    return@withLock
                }
                rules.update { it.copy(volume = volume) }
                val target = storage.audioDir(volume)
                var moved = 0
                for (row in dao.all()) {
                    val path = row.path ?: continue
                    val file = File(path)
                    if (!file.exists() || file.parentFile == target) continue
                    val dest = File(target, file.name)
                    runCatching {
                        file.copyTo(dest, overwrite = true)
                        dao.movePath(row.trackId, dest.path)
                        file.delete()
                        moved++
                    }
                }
                Toasts.show(if (volume == StorageVolume.SdCard) "Загрузки перенесены на карту памяти ($moved)" else "Загрузки перенесены в память телефона ($moved)")
            }
        }
    }

    fun retryFailed() {
        scope.launch {
            dao.retryFailed()
            sync.scheduleDownloads(rules.current())
        }
    }

    fun syncNow() {
        scope.launch {
            if (sync.sync()) Toasts.show("Загрузки сверены с Sukify") else Toasts.show("Нет связи с сервером — сверю позже")
        }
    }

    /** «Удалить все загрузки»: выключить всё и стереть файлы. */
    fun removeAll() {
        scope.launch {
            rules.update {
                it.copy(
                    autoLiked = false,
                    autoAlbums = false,
                    autoPlaylists = false,
                    autoArtists = false,
                    includes = emptySet(),
                    excludes = emptySet(),
                )
            }
            sync.rulesChanged()
            Toasts.show("Все загрузки удалены")
        }
    }
}
