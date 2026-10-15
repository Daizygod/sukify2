package ru.nepalimsya.sukify.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Подключение интерфейса к сервису воспроизведения. Команды интерфейс шлёт
 * движку напрямую (тот же процесс, без сериализации), а контроллер нужен,
 * чтобы сервис был запущен и по правилам Media3 уходил в foreground на время
 * музыки — иначе в фоне её бы остановила система.
 */
@Singleton
class PlaybackConnection @Inject constructor(@ApplicationContext private val context: Context) {

    private var controller: ListenableFuture<MediaController>? = null

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controller = MediaController.Builder(context, token).buildAsync()
    }

    fun disconnect() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
    }
}
