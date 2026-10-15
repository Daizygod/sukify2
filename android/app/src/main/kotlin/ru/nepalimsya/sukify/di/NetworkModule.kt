package ru.nepalimsya.sukify.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.core.datastore.KeystoreSecretBox
import ru.nepalimsya.sukify.core.datastore.SecretBox
import ru.nepalimsya.sukify.core.network.ApiHost
import ru.nepalimsya.sukify.core.network.ApiInterceptor
import ru.nepalimsya.sukify.core.network.EmulatorLoopbackInterceptor
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.data.offline.OfflineCoverInterceptor
import ru.nepalimsya.sukify.data.offline.OfflineStorage

/** Клиент для API (с токеном) — в отличие от «голого» для файлов из S3. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApiClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Базовый клиент: общий пул соединений. Обложки, аудио и загрузки идут
     * прямо в публичный S3 — им токен не нужен и не должен утекать.
     */
    @Provides
    @Singleton
    fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG && EmulatorLoopbackInterceptor.isEmulator) {
                addInterceptor(EmulatorLoopbackInterceptor())
            }
        }
        .build()

    @Provides
    @Singleton
    @ApiClient
    fun apiClient(base: OkHttpClient, apiInterceptor: ApiInterceptor): OkHttpClient =
        base.newBuilder()
            .addInterceptor(apiInterceptor)
            .apply {
                // Адрес сервера подставляет apiInterceptor — localhost эмулятора
                // переводим уже после него.
                if (BuildConfig.DEBUG && EmulatorLoopbackInterceptor.isEmulator) {
                    addInterceptor(EmulatorLoopbackInterceptor())
                }
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        okhttp3.logging.HttpLoggingInterceptor().apply {
                            level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
            .build()

    @Provides
    @Singleton
    fun retrofit(@ApiClient client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(ApiHost.PLACEHOLDER)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun sukifyApi(retrofit: Retrofit): SukifyApi = retrofit.create(SukifyApi::class.java)

    @Provides
    @Singleton
    fun imageLoader(@ApplicationContext context: Context, base: OkHttpClient, storage: OfflineStorage): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                // Обложки скачанного — с диска (работают без сети).
                add(OfflineCoverInterceptor(storage))
                add(OkHttpNetworkFetcherFactory(callFactory = { base }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(160)
            .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    @Binds
    abstract fun secretBox(impl: KeystoreSecretBox): SecretBox
}
