package ru.nepalimsya.sukify.playback

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.data.db.PendingPlayDao
import ru.nepalimsya.sukify.data.db.PendingPlayEntity
import ru.nepalimsya.sukify.di.ApplicationScope

/**
 * Прослушивания: на «Главной» — «Недавно прослушано», в статистике — часы,
 * у друзей — «сейчас слушает». Без сети прослушивание копится в базе и
 * уходит пачкой с исходным временем, как только сеть вернётся.
 */
@Singleton
class PlayLogger @Inject constructor(
    private val api: SukifyApi,
    private val pending: PendingPlayDao,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val flushLock = Mutex()

    fun log(trackId: Long) {
        val startedAt = Instant.now().epochSecond
        scope.launch {
            val sent = runCatching { api.logPlay(trackId) }.isSuccess
            if (!sent) {
                pending.insert(PendingPlayEntity(trackId = trackId, playedAt = startedAt))
            } else {
                flush()
            }
        }
    }

    /** Отправить накопленное. Сервер сам отбрасывает повторы пачки. */
    suspend fun flush() = flushLock.withLock {
        while (true) {
            val batch = pending.oldest(200)
            if (batch.isEmpty()) return@withLock
            val body = buildJsonObject {
                putJsonArray("plays") {
                    batch.forEach { play ->
                        add(
                            buildJsonObject {
                                put("track_id", play.trackId)
                                put("played_at", Instant.ofEpochSecond(play.playedAt).toString())
                            },
                        )
                    }
                }
            }
            if (runCatching { api.logPlays(body) }.isFailure) return@withLock
            pending.delete(batch.map { it.id })
        }
    }
}
