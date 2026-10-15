package ru.nepalimsya.sukify.playback

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.ui.components.Toasts

/** Таймер сна: выключен, до момента (elapsedRealtime) или до конца трека. */
@Immutable
sealed interface SleepState {
    data object Off : SleepState

    data class Until(val endAt: Long, val minutes: Int) : SleepState

    data object EndOfTrack : SleepState
}

/**
 * Таймер сна (сверх веба): через 5…60 минут или в конце трека музыка
 * плавно гаснет за ~10 секунд и встаёт на паузу. Работает с выключенным
 * экраном — считает движок, а не интерфейс.
 */
@Singleton
class SleepTimer @Inject constructor(private val engine: PlaybackEngine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _state = MutableStateFlow<SleepState>(SleepState.Off)
    val state: StateFlow<SleepState> = _state.asStateFlow()

    init {
        engine.onStoppedAfterCurrent = { finished() }
    }

    fun start(minutes: Int) {
        cancel(silent = true)
        val endAt = SystemClock.elapsedRealtime() + minutes * 60_000L
        _state.value = SleepState.Until(endAt, minutes)
        job = scope.launch {
            delay((endAt - SystemClock.elapsedRealtime() - PlaybackEngine.SLEEP_FADE_MS).coerceAtLeast(0))
            // Музыку уже остановили сами — тихо выключаемся.
            if (!engine.localState.value.playing) {
                _state.value = SleepState.Off
                return@launch
            }
            engine.fadeOutAndPause(PlaybackEngine.SLEEP_FADE_MS) { finished() }
        }
        Toasts.show("Таймер сна: ${minutesText(minutes)}")
    }

    fun endOfTrack() {
        cancel(silent = true)
        _state.value = SleepState.EndOfTrack
        engine.stopAfterCurrent = true
        Toasts.show("Таймер сна: до конца трека")
    }

    fun cancel(silent: Boolean = false) {
        job?.cancel()
        job = null
        engine.stopAfterCurrent = false
        val wasOn = _state.value != SleepState.Off
        _state.value = SleepState.Off
        if (wasOn && !silent) Toasts.show("Таймер сна выключен")
    }

    /** Сколько осталось, мс — для обратного отсчёта на экране. */
    fun remainingMs(): Long? = (state.value as? SleepState.Until)?.let { (it.endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) }

    private fun finished() {
        job = null
        engine.stopAfterCurrent = false
        _state.value = SleepState.Off
        Toasts.show("Спокойной ночи — таймер выключил музыку")
    }

    companion object {
        val OPTIONS = listOf(5, 10, 15, 30, 45, 60)

        fun minutesText(minutes: Int): String = if (minutes == 60) "1 час" else "$minutes минут"
    }
}
