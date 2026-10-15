package ru.nepalimsya.sukify.screenshots

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.nepalimsya.sukify.ui.auth.AuthContent
import ru.nepalimsya.sukify.ui.auth.AuthForm
import ru.nepalimsya.sukify.ui.auth.AuthMode
import ru.nepalimsya.sukify.ui.theme.SukifyTheme

/** Форма входа без ViewModel — для скриншотов. */
private class FakeAuthForm(
    override val mode: AuthMode = AuthMode.Login,
    override val error: String? = null,
    override val fieldErrors: Map<String, String> = emptyMap(),
    override val busy: Boolean = false,
) : AuthForm {
    override var name by mutableStateOf("")
    override var email by mutableStateOf("")
    override var password by mutableStateOf("")
    override var confirmation by mutableStateOf("")
    override var server by mutableStateOf("https://sukify.nepalimsya.ru")

    override fun toggleMode() = Unit

    override fun submit() = Unit
}

/**
 * Скриншоты экранов каркаса (Roborazzi): PNG кладутся в app/screenshots.
 * Запуск: ./gradlew :app:recordRoborazziDebug
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class ShellScreenshotTest {

    @Test
    fun authLogin() {
        captureRoboImage("screenshots/auth_login.png") {
            SukifyTheme {
                AuthContent(FakeAuthForm().apply { email = "demo@sukify.test"; password = "password" })
            }
        }
    }

    @Test
    fun authRegisterWithError() {
        captureRoboImage("screenshots/auth_register_error.png") {
            SukifyTheme {
                AuthContent(
                    FakeAuthForm(
                        mode = AuthMode.Register,
                        error = "Эта почта уже зарегистрирована",
                        fieldErrors = mapOf("email" to "Эта почта уже зарегистрирована"),
                    ).apply {
                        name = "Руслан"
                        email = "demo@sukify.test"
                    },
                )
            }
        }
    }
}
