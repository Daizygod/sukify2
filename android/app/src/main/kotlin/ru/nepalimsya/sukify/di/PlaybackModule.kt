package ru.nepalimsya.sukify.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.nepalimsya.sukify.data.offline.OfflineState
import ru.nepalimsya.sukify.playback.OfflineFiles

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {

    /** Скачанные файлы плеер берёт у загрузок: есть файл — играет без сети. */
    @Binds
    abstract fun offlineFiles(impl: OfflineState): OfflineFiles
}
