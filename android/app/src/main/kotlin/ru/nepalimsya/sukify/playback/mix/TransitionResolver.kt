package ru.nepalimsya.sukify.playback.mix

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.model.TransitionDto
import ru.nepalimsya.sukify.data.offline.OfflineTransitionDao
import ru.nepalimsya.sukify.di.ApplicationScope
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.TransitionPlan
import ru.nepalimsya.sukify.playback.audio.CurveType

/**
 * Какой переход играть между парой треков: личный выбор или лучший переход
 * сообщества (GET /transitions), без сети — скачанный вместе с треками;
 * нет своего — глобальный кроссфейд из настроек.
 *
 * Плеер готовит следующий трек за 20 секунд до конца и решает синхронно,
 * поэтому переход пары запрашивается заранее — как только пара известна.
 * Кэшируем сам запрос (как промис в вебе): пока первый в полёте, второй не
 * уходит.
 */
@Singleton
class TransitionResolver @Inject constructor(
    private val api: SukifyApi,
    private val offline: OfflineTransitionDao,
    private val json: Json,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val cache = ConcurrentHashMap<String, Deferred<TransitionDto?>>()

    private fun key(from: Track, to: Track) = "${from.id}:${to.id}"

    /** Начать узнавать переход пары, если ещё не узнавали. */
    fun prefetch(from: Track, to: Track) {
        cache.computeIfAbsent(key(from, to)) { scope.async { fetch(from.id, to.id) } }
    }

    /** Переход пары ещё узнаём — плееру стоит подождать с подготовкой. */
    fun isPending(from: Track, to: Track): Boolean = cache[key(from, to)]?.isCompleted == false

    /** Переход пары, если уже известен (null — не известен или его нет). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun known(from: Track, to: Track): TransitionDto? =
        cache[key(from, to)]?.takeIf { it.isCompleted && !it.isCancelled }?.getCompleted()

    /** Сохранили или выбрали переход в редакторе — забыть кэш. */
    fun invalidate(fromId: Long? = null, toId: Long? = null) {
        if (fromId == null || toId == null) cache.clear() else cache.remove("$fromId:$toId")
    }

    private suspend fun fetch(from: Long, to: Long): TransitionDto? {
        val online = apiCall { api.transition(from, to) }
        if (online.isSuccess) return online.getOrNull()?.data
        // Без сети — переход, скачанный вместе с треками.
        val row = offline.get(from, to) ?: return null
        return row.json?.let { runCatching { json.decodeFromString(TransitionDto.serializer(), it) }.getOrNull() }
    }

    /**
     * План перехода: где начинать затухание уходящего, откуда играть
     * приходящий, сколько длится перекрытие и какими формами.
     */
    fun plan(from: Track, to: Track, fromDurationMs: Long, toDurationMs: Long, defaultCrossfadeMs: Long): TransitionPlan {
        val t = known(from, to) ?: return globalCrossfade(fromDurationMs, toDurationMs, defaultCrossfadeMs)
        return planFor(t, from.bpm?.toDouble(), to.bpm?.toDouble(), fromDurationMs, toDurationMs)
    }

    companion object {
        /** Глобальный кроссфейд из настроек (как в вебе: равная мощность). */
        fun globalCrossfade(fromDurationMs: Long, toDurationMs: Long, crossfadeMs: Long): TransitionPlan {
            val length = minOf(crossfadeMs, minOf(fromDurationMs, toDurationMs) / 2).coerceAtLeast(0)
            return TransitionPlan(outStartMs = fromDurationMs - length, inStartMs = 0, lengthMs = length)
        }

        /**
         * Переход сообщества → план. Перекрытие — длина затухания уходящего;
         * точки входа и выхода — из редактора (не только «в конце трека»).
         */
        fun planFor(t: TransitionDto, fromBpm: Double?, toBpm: Double?, fromDurationMs: Long, toDurationMs: Long): TransitionPlan {
            val length = (t.fadeOutEndMs - t.fadeOutStartMs).coerceAtLeast(0)
            if (length == 0L) {
                // «Без перекрытия» — стык прямо в конце уходящего.
                return TransitionPlan(outStartMs = fromDurationMs, inStartMs = t.fadeInStartMs.coerceAtLeast(0), lengthMs = 0)
            }
            val outStart = t.fadeOutStartMs.coerceIn(0, (fromDurationMs - length).coerceAtLeast(0))
            val inStart = t.fadeInStartMs.coerceIn(0, (toDurationMs - length).coerceAtLeast(0))
            val shapes = t.preset?.let {
                MixShapes.resolve(it, t.volumeShape, t.eqShape, t.filterShape, MixShapes.beatsMatch(fromBpm, toBpm))
            }
            return TransitionPlan(outStart, inStart, length, CurveType.fromWire(t.curveType), shapes)
        }
    }
}
