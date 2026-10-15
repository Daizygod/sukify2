package ru.nepalimsya.sukify.playback

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/** Настройки воспроизведения — общие с вебом (GET /playback-settings). */
@Immutable
data class PlaybackPrefs(
    /** Цель нормализации; −29,9 веб ставит, когда нормализация выключена. */
    val targetLufs: Double = -14.0,
    val crossfadeSeconds: Int = 0,
    val smartShuffle: Boolean = false,
) {
    val normalizationEnabled: Boolean get() = targetLufs > -29.0

    /**
     * Множитель громкости трека к цели (как normGain в player.js):
     * 10^((цель − LUFS)/20), не тише ×0,05 и не громче ×4.
     */
    fun gainFor(loudnessLufs: Double?): Float {
        if (loudnessLufs == null) return 1f
        val g = 10.0.pow((targetLufs - loudnessLufs) / 20.0)
        return g.coerceIn(0.05, 4.0).toFloat()
    }
}

@Singleton
class PlaybackSettings @Inject constructor(private val api: SukifyApi) {

    private val _prefs = MutableStateFlow(PlaybackPrefs())
    val prefs: StateFlow<PlaybackPrefs> = _prefs.asStateFlow()

    val current: PlaybackPrefs get() = _prefs.value

    suspend fun refresh() {
        runCatching { api.playbackSettings().data }.onSuccess {
            _prefs.value = PlaybackPrefs(it.targetLoudnessLufs, it.defaultCrossfadeSeconds, it.smartShuffleEnabled)
        }
    }

    fun update(prefs: PlaybackPrefs) {
        _prefs.value = prefs
    }

    /** Сохранить на сервере (общие с вебом) и сразу применить у себя. */
    suspend fun save(prefs: PlaybackPrefs): Boolean {
        val before = _prefs.value
        _prefs.value = prefs
        val body = buildJsonObject {
            put("default_crossfade_seconds", prefs.crossfadeSeconds)
            put("target_loudness_lufs", prefs.targetLufs)
            put("smart_shuffle_enabled", prefs.smartShuffle)
        }
        return apiCall { api.savePlaybackSettings(body) }.onFailure { _prefs.value = before }.isSuccess
    }
}
