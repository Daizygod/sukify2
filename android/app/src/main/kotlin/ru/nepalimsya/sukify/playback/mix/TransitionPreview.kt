package ru.nepalimsya.sukify.playback.mix

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.Deck
import ru.nepalimsya.sukify.playback.MediaSources
import ru.nepalimsya.sukify.playback.TransitionPlan

/**
 * Предпрослушка перехода в редакторе (lib/mix/preview.js): играет только
 * стык — пару секунд до перекрытия, само перекрытие и немного нового трека.
 * Свои две деки с той же цепочкой обработки, что у плеера, — услышанное
 * здесь и прозвучит в миксе. Основной плеер на это время ставим на паузу.
 */
class TransitionPreview(
    context: Context,
    sources: MediaSources,
    private val gainFor: (Track) -> Float,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decks = arrayOf(Deck(context, sources, 10), Deck(context, sources, 11))
    private var job: Job? = null

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /** Где идёт звук: 0..1 — доля перекрытия; меньше — разгон, больше — хвост; null — тишина. */
    private val _playhead = MutableStateFlow<Float?>(null)
    val playhead: StateFlow<Float?> = _playhead.asStateFlow()

    fun play(from: Track, to: Track, plan: TransitionPlan) {
        stop()
        val (outAuto, inAuto) = MixAutomations.forPlan(plan)
        val lead = minOf(LEAD_MS, plan.outStartMs)
        val a = decks[0]
        val b = decks[1]
        a.load(from, plan.outStartMs - lead, gainFor(from), outAuto)
        b.load(to, plan.inStartMs, gainFor(to), inAuto)
        a.player.playWhenReady = true
        _playing.value = true
        job = scope.launch {
            var started = false
            val length = plan.lengthMs.coerceAtLeast(1)
            while (isActive) {
                val pos = a.positionMs
                if (!started && pos >= plan.outStartMs) {
                    // Опоздали со стартом — сдвигаем огибающие, чтобы кривые шли вместе.
                    val late = pos - plan.outStartMs
                    if (late > 15) b.processor.automation = inAuto.shiftedBy(-late * 1000)
                    b.player.playWhenReady = true
                    started = true
                }
                _playhead.value = (pos - plan.outStartMs).toFloat() / length
                if (pos >= plan.outStartMs + plan.lengthMs + TAIL_MS || (started && b.player.playbackState == androidx.media3.common.Player.STATE_ENDED)) break
                delay(16)
            }
            stop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        decks.forEach { it.clear() }
        _playing.value = false
        _playhead.value = null
    }

    fun release() {
        stop()
        decks.forEach { it.release() }
        scope.cancel()
    }

    private companion object {
        const val LEAD_MS = 2_000L
        const val TAIL_MS = 2_500L
    }
}
