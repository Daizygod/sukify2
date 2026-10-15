package ru.nepalimsya.sukify.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Аудиофокус и «выдернули наушники» — за обе деки сразу.
 *
 * Звонок или навигатор: короткая потеря — пауза и возобновление после,
 * «можно приглушить» — тише на время, насовсем (другой плеер) — пауза.
 */
class AudioFocus(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onFocusLost()

        fun onFocusLostTransient()

        fun onDuck(ducked: Boolean)

        fun onFocusRegained()

        fun onBecomingNoisy()
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var hasFocus = false
    private var noisyRegistered = false

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change -> onChange(change) }, handler)
        .build()

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) listener.onBecomingNoisy()
        }
    }

    /** true — можно играть. */
    fun request(): Boolean {
        if (hasFocus) return true
        hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) registerNoisy()
        return hasFocus
    }

    fun abandon() {
        if (hasFocus) audioManager.abandonAudioFocusRequest(request)
        hasFocus = false
        unregisterNoisy()
    }

    private fun onChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                listener.onDuck(false)
                listener.onFocusRegained()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                unregisterNoisy()
                listener.onFocusLost()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> listener.onFocusLostTransient()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener.onDuck(true)
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            context,
            noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { context.unregisterReceiver(noisy) }
        noisyRegistered = false
    }
}
