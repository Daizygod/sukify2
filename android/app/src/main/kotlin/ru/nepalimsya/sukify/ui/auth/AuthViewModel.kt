package ru.nepalimsya.sukify.ui.auth

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.network.apiError
import ru.nepalimsya.sukify.data.auth.AuthRepository

/** Состояние формы входа — экран рисуется по нему (и в тестах — по подделке). */
@Stable
interface AuthForm {
    var email: String
    var password: String
    var server: String
    val busy: Boolean
    val error: String?

    /** Ошибки по полям из ответа сервера: поле → текст. */
    val fieldErrors: Map<String, String>

    fun submit()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel(), AuthForm {

    override var email by mutableStateOf("")
    override var password by mutableStateOf("")
    override var server by mutableStateOf("")

    override var busy by mutableStateOf(false)
        private set
    override var error by mutableStateOf<String?>(null)
        private set

    override var fieldErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    override fun submit() {
        if (busy) return
        val problem = validate()
        if (problem != null) {
            error = problem
            return
        }

        busy = true
        error = null
        fieldErrors = emptyMap()
        viewModelScope.launch {
            val result = auth.login(server, email, password)
            // Успех переключит экран сам: SessionStore сообщит о новой сессии.
            // Пароль из формы — забыть: после выхода войти снова должен тот, кто его знает.
            result.onSuccess { password = "" }
            result.onFailure {
                val apiError = it.apiError
                error = apiError.message
                fieldErrors = apiError.fields
            }
            busy = false
        }
    }

    private fun validate(): String? = when {
        server.isBlank() -> "Укажи адрес сервера"
        email.isBlank() || '@' !in email -> "Нужна электронная почта"
        password.isEmpty() -> "Нужен пароль"
        else -> null
    }
}
