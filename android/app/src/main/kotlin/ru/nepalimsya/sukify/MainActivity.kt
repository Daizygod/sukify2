package ru.nepalimsya.sukify

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import ru.nepalimsya.sukify.connect.ConnectManager
import ru.nepalimsya.sukify.data.offline.OfflineCoordinator
import ru.nepalimsya.sukify.ui.AppState
import ru.nepalimsya.sukify.ui.AppViewModel
import ru.nepalimsya.sukify.ui.SukifyRoot
import ru.nepalimsya.sukify.ui.components.Toasts
import ru.nepalimsya.sukify.ui.shell.DeepLinks
import ru.nepalimsya.sukify.ui.theme.SukifyTheme
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    @Inject lateinit var offline: OfflineCoordinator

    @Inject lateinit var connect: ConnectManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Сплэш висит, пока не прочитана сессия: так первым кадром сразу будет
        // нужный экран — вход или главная, без мигания.
        splash.setKeepOnScreenCondition { appViewModel.state.value is AppState.Loading }

        // Тёмный интерфейс рисуется под системными панелями; значки панелей светлые.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        // Открыли ссылку на сайт — нужный экран откроется, как только будет готова оболочка.
        if (savedInstanceState == null) DeepLinks.offer(intent?.dataString)

        setContent {
            SukifyTheme {
                SukifyRoot(appViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DeepLinks.offer(intent.dataString)
    }

    /**
     * Кнопки громкости, пока играет другое устройство (Connect), — громкость
     * того устройства, как в Spotify.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VOLUME_STEP
            KeyEvent.KEYCODE_VOLUME_DOWN -> -VOLUME_STEP
            else -> return super.dispatchKeyEvent(event)
        }
        val volume = connect.remote.value?.vol
        if (!connect.active || volume == null) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val next = (volume + step).coerceIn(0f, 1f)
            connect.setRemoteVolume(next)
            Toasts.show("Громкость: ${(next * 100).roundToInt()}%")
        }
        return true
    }

    /** Вернулись в приложение: давно не сверяли загрузки — сверим. */
    override fun onStart() {
        super.onStart()
        offline.onForeground()
    }
}

private const val VOLUME_STEP = 1f / 16
