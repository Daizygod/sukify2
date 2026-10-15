package ru.nepalimsya.sukify.data.offline

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import ru.nepalimsya.sukify.core.network.NetworkMonitor
import ru.nepalimsya.sukify.core.realtime.Realtime
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.di.ApplicationScope
import ru.nepalimsya.sukify.playback.PlayLogger

/**
 * Когда сверять загрузки с сервером:
 *  - при входе и возвращении в приложение (если давно не сверялись);
 *  - когда появилась сеть;
 *  - когда медиатеку поменяли здесь (лайк, плейлист) или где угодно ещё —
 *    сервер шлёт library-changed в личный канал;
 *  - когда в каталоге что-то удалили или заменили файл (catalog-changed);
 *  - раз в 12 часов фоном (OfflineSyncWorker).
 */
@Singleton
class OfflineCoordinator @Inject constructor(
    private val sync: OfflineSync,
    private val library: LibraryState,
    private val network: NetworkMonitor,
    private val realtime: Realtime,
    private val plays: PlayLogger,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val jobs = mutableListOf<Job>()

    fun start() {
        if (jobs.isNotEmpty()) return
        sync.start()
        sync.requestSync("start", delayMs = 1_500)
        jobs += scope.launch { library.changes.collect { sync.requestSync("library:$it") } }
        jobs += scope.launch {
            network.state.map { it.online }.distinctUntilChanged().drop(1).filter { it }.collect {
                sync.requestSync("online", delayMs = 1_000)
                // Прослушивания и лайки, накопленные без сети, — на сервер.
                plays.flush()
                library.refresh()
                // Запустились без сети — реалтайм так и не подключился; подключаем теперь.
                realtime.start()
            }
        }
        jobs += scope.launch {
            realtime.start()
            realtime.events.collect { event ->
                when (event["t"]?.jsonPrimitive?.contentOrNull) {
                    "library-changed" -> {
                        // С другого устройства: обновить медиатеку на экранах и загрузки.
                        library.refresh()
                        sync.requestSync("server:library")
                    }
                    "catalog-changed" -> sync.requestSync("server:catalog")
                }
            }
        }
        // Связь вернулась после обрыва — за это время могли что-то поменять.
        jobs += scope.launch {
            realtime.connected.drop(1).filter { it }.collect { sync.requestSync("realtime", delayMs = 2_000) }
        }
    }

    /** Приложение снова на экране: давно не сверялись — сверяемся. */
    fun onForeground() {
        if (jobs.isEmpty()) return
        val last = sync.status.value.lastSyncAt
        if (System.currentTimeMillis() - last > FOREGROUND_SYNC_MS) sync.requestSync("foreground", delayMs = 500)
    }

    /** Выход из аккаунта: больше не слушаем события и стираем загрузки. */
    suspend fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        realtime.stop()
        sync.wipe()
    }

    private companion object {
        const val FOREGROUND_SYNC_MS = 15 * 60_000L
    }
}
