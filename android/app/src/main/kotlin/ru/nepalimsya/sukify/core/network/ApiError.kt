package ru.nepalimsya.sukify.core.network

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/** Ошибка запроса в виде, который можно показать человеку. */
data class ApiError(
    val message: String,
    val code: Int? = null,
    /** Ошибки по полям формы (422 от Laravel): поле → текст. */
    val fields: Map<String, String> = emptyMap(),
) {
    val isNetwork: Boolean get() = code == null

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun from(error: Throwable): ApiError = when (error) {
            is HttpException -> fromHttp(error.code(), error.response()?.errorBody()?.string())
            is IOException -> ApiError("Нет связи с сервером. Проверь интернет и адрес сервера.")
            else -> ApiError(error.message ?: "Что-то пошло не так")
        }

        fun fromHttp(code: Int, body: String?): ApiError {
            val parsed = body?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            val serverMessage = parsed?.get("message")?.jsonPrimitive?.contentOrNull
            val fields = parsed?.get("errors")?.let { parseFields(it as? JsonObject) }.orEmpty()

            val message = when (code) {
                422 -> fields.values.firstOrNull() ?: translate(serverMessage) ?: "Проверь введённые данные"
                401 -> "Сессия истекла — войди заново"
                403 -> if (serverMessage?.contains("suspended", true) == true) {
                    "Аккаунт заблокирован"
                } else {
                    translate(serverMessage) ?: "Нет доступа"
                }
                404 -> "Не нашлось — возможно, это удалили"
                429 -> "Слишком много попыток — подожди минуту"
                in 500..599 -> "Сервер не отвечает, попробуй позже"
                else -> translate(serverMessage) ?: "Ошибка сервера ($code)"
            }
            return ApiError(message, code, fields)
        }

        private fun parseFields(errors: JsonObject?): Map<String, String> = errors
            ?.mapNotNull { (field, value) ->
                val first = (value as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
                first?.let { field to (translate(it, field) ?: it) }
            }
            ?.toMap()
            .orEmpty()

        /**
         * Валидация Laravel по умолчанию отвечает по-английски — частые
         * сообщения переводим, остальные показываем как есть.
         */
        internal fun translate(message: String?, field: String? = null): String? {
            if (message == null) return null
            val m = message.lowercase()
            return when {
                "has already been taken" in m && field == "email" -> "Эта почта уже зарегистрирована"
                "has already been taken" in m && field == "username" -> "Этот @handle уже занят"
                "confirmation does not match" in m -> "Пароли не совпадают"
                "must be at least" in m && field == "password" -> "Пароль — минимум 8 символов"
                "must be a valid email" in m -> "Похоже, в почте опечатка"
                "is required" in m -> "Заполни все поля"
                "credentials are incorrect" in m -> "Неверная почта или пароль"
                "suspended" in m -> "Аккаунт заблокирован"
                message.any { it in 'а'..'я' || it in 'А'..'Я' } -> message
                else -> null
            }
        }
    }
}

/** Результат запроса: значение или понятная ошибка. */
suspend inline fun <T> apiCall(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(ApiException(ApiError.from(e), e))
    }

class ApiException(val error: ApiError, cause: Throwable? = null) : Exception(error.message, cause)

val Throwable.apiError: ApiError get() = (this as? ApiException)?.error ?: ApiError.from(this)
