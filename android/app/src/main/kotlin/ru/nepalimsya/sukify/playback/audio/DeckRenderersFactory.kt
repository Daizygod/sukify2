package ru.nepalimsya.sukify.playback.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Рендереры деки: стандартные, только в аудиоцепочку вставлен наш DeckProcessor. */
class DeckRenderersFactory(
    context: Context,
    private val processor: DeckProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessors(arrayOf(processor))
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()
}
