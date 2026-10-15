package ru.nepalimsya.sukify.playback

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.audio.DeckProcessor
import ru.nepalimsya.sukify.playback.audio.DeckRenderersFactory
import ru.nepalimsya.sukify.playback.audio.DeckAutomation

/**
 * Дека — один ExoPlayer с одним треком и своей звуковой цепочкой.
 * Две деки нужны для переходов: пока уходящий трек затухает на одной,
 * приходящий уже играет на другой.
 *
 * Аудиофокусом и «наушники выдернули» деки не занимаются — этим управляет
 * движок: иначе две деки отбирали бы фокус друг у друга.
 */
class Deck(context: Context, sources: MediaSources, val index: Int) {

    val processor = DeckProcessor()

    val player: ExoPlayer = ExoPlayer.Builder(context, DeckRenderersFactory(context, processor))
        .setMediaSourceFactory(sources.mediaSourceFactory())
        .setAudioAttributes(MUSIC, false)
        .setHandleAudioBecomingNoisy(false)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setLooper(Looper.getMainLooper())
        .build()

    private val sources = sources

    /** Что загружено в деку. */
    var track: Track? = null
        private set

    /** Загрузить трек с позиции [startMs], не запуская звук. */
    fun load(track: Track, startMs: Long, normGain: Float, automation: DeckAutomation = DeckAutomation.Neutral) {
        this.track = track
        processor.normGain = normGain
        processor.automation = automation
        // Медиавремя первого сэмпла после сброса — огибающая отсчитывает от него.
        processor.pendingFlushPositionUs = startMs * 1000
        player.playWhenReady = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setMediaItem(sources.mediaItem(track), startMs)
        player.prepare()
    }

    fun seekTo(ms: Long) {
        processor.pendingFlushPositionUs = ms * 1000
        player.seekTo(ms)
    }

    /** Освободить деку под следующий трек. */
    fun clear() {
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        processor.automation = DeckAutomation.Neutral
        track = null
    }

    val positionMs: Long get() = player.currentPosition

    val durationMs: Long
        get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (track?.durationMs ?: 0)

    val isLoaded: Boolean get() = track != null

    fun release() = player.release()

    companion object {
        val MUSIC: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }
}
