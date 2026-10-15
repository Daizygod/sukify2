package ru.nepalimsya.sukify

import android.app.Application
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.di.ApplicationScope
import ru.nepalimsya.sukify.widget.WidgetUpdater
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class SukifyApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var imageLoader: Provider<ImageLoader>

    @Inject lateinit var widgets: dagger.Lazy<WidgetUpdater>

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        // Отладка: диск и сеть на главном потоке, утечки — в logcat (тег StrictMode).
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build(),
            )
        }
        super.onCreate()
        // Виджет на рабочем столе следит за плеером, пока жив процесс. Плеер со всем,
        // что ему нужно (сеть с TLS, кэш, настройки), собираем в фоне — первый кадр
        // этого не ждёт, а экран получит уже готовый.
        appScope.launch(Dispatchers.Default) { widgets.get().start() }
        // Настройки, которые экраны читают сразу после старта, — с диска заранее,
        // чтобы главный поток их не ждал.
        appScope.launch(Dispatchers.IO) {
            WARM_PREFS.forEach { getSharedPreferences(it, MODE_PRIVATE).all }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /** Один загрузчик картинок на всё приложение — с общими кэшами. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader.get()

    private companion object {
        val WARM_PREFS = listOf("connect", "jam", "updates", "equalizer")
    }
}
