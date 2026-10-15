package ru.nepalimsya.sukify.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ru.nepalimsya.sukify.core.network.ApiInterceptor
import ru.nepalimsya.sukify.di.ApplicationScope
import ru.nepalimsya.sukify.domain.Track

/** Где лежит скачанный файл трека — отвечает модуль загрузок (фаза 4). */
fun interface OfflineFiles {
    /** Файл с той же ревизией аудио или null, если трек не скачан. */
    fun fileFor(track: Track): File?
}

/**
 * Откуда играть трек:
 *  1. скачанный файл с той же ревизией — мгновенно и без сети;
 *  2. кэш прослушанного (LRU) — повторное прослушивание тоже без сети;
 *  3. сеть — прямо из S3, параллельно ложится в кэш.
 *
 * Ключ кэша — id трека и ревизия файла, а не адрес: ?v= в ссылке может
 * поменяться, а файл — нет, и наоборот.
 */
@Singleton
class MediaSources @Inject constructor(
    @ApplicationContext private val context: Context,
    baseClient: OkHttpClient,
    private val offline: OfflineFiles,
    @ApplicationScope scope: CoroutineScope,
) {
    /** Кэш прослушанного: один экземпляр на процесс (так требует SimpleCache). */
    val streamCache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "stream"),
            LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }

    init {
        // Индекс кэша читается с диска — поднимаем заранее и в фоне, а не на первом ▶.
        scope.launch(Dispatchers.IO) { streamCache }
    }

    /** Сколько занимает кэш прослушанного, байт (для настроек). */
    fun cacheBytes(): Long = streamCache.cacheSpace

    /**
     * Очистить кэш прослушанного. Скачанное не трогаем — оно отдельно.
     * Играющий сейчас трек доиграет: его данные уже в буфере плеера.
     */
    fun clearCache() {
        for (key in streamCache.keys.toList()) runCatching { streamCache.removeResource(key) }
    }

    private val http = OkHttpDataSource.Factory(baseClient).setUserAgent(ApiInterceptor.USER_AGENT)

    private val cached: CacheDataSource.Factory by lazy {
        CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** file:// → FileDataSource, http(s) → кэш + сеть. */
    private val dataSources by lazy { DefaultDataSource.Factory(context, cached) }

    // Точная перемотка по MP3 с постоянным битрейтом — без чтения всего файла.
    private val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)

    fun mediaSourceFactory(): MediaSource.Factory = DefaultMediaSourceFactory(dataSources, extractors)

    fun mediaItem(track: Track): MediaItem {
        val local = offline.fileFor(track)
        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(local?.toUri() ?: track.streamUrl!!.toUri())
            .setCustomCacheKey(track.cacheKey)
            .build()
    }

    fun isLocal(track: Track): Boolean = offline.fileFor(track) != null

    companion object {
        const val STREAM_CACHE_BYTES = 1024L * 1024 * 1024
    }
}
