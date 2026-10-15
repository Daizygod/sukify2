package ru.nepalimsya.sukify.data.offline

import android.net.Uri
import coil3.intercept.Interceptor
import coil3.request.ImageResult

/**
 * Обложки скачанного — с диска, а не из сети: без интернета они на месте,
 * а с интернетом не тратят трафик. Ссылку S3 вида …/covers/5/300.webp?v=…
 * подменяем на локальный файл той же версии.
 */
class OfflineCoverInterceptor(private val storage: OfflineStorage) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data as? String ?: return chain.proceed()
        val match = COVER.find(data) ?: return chain.proceed()
        val kind = if (match.groupValues[1] == "covers") 'r' else 't'
        val id = match.groupValues[2].toLongOrNull() ?: return chain.proceed()
        val size = match.groupValues[3].toIntOrNull() ?: return chain.proceed()
        val version = if (kind == 'r') runCatching { Uri.parse(data).getQueryParameter("v")?.toLongOrNull() }.getOrNull() else null
        val file = storage.coverFile(kind, id, size, version) ?: return chain.proceed()
        return chain.withRequest(chain.request.newBuilder().data(file).build()).proceed()
    }

    private companion object {
        val COVER = Regex("/(covers|track-covers)/(\\d+)/(\\d+)\\.webp")
    }
}
