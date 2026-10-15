package ru.nepalimsya.sukify.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Обложки для уведомления, экрана блокировки и Android Auto — через тот же
 * Coil, что и в интерфейсе: общий дисковый кэш (обложка уже скачана — сети
 * не нужно) и общий HTTP-клиент. Стандартный загрузчик Media3 ходит в сеть
 * сам, мимо кэша.
 */
@UnstableApi
class CoilBitmapLoader(
    private val context: Context,
    private val imageLoader: ImageLoader,
) : BitmapLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = future {
        BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Не удалось декодировать обложку")
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = future {
        val request = ImageRequest.Builder(context)
            .data(uri.toString())
            .size(MAX_SIDE_PX)
            // Аппаратный битмап не передать в уведомление между процессами.
            .allowHardware(false)
            .build()
        val result = imageLoader.execute(request)
        (result as? SuccessResult)?.image?.toBitmap() ?: error("Обложка не загрузилась: $uri")
    }

    fun release() = scope.cancel()

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            runCatching { block() }
                .onSuccess { future.set(it) }
                .onFailure { future.setException(it) }
        }
        return future
    }

    private companion object {
        /** Уведомлению и машине больше 1000 px не нужно — рендишн веба такого же размера. */
        const val MAX_SIDE_PX = 1000
    }
}
