package ru.nepalimsya.sukify.core.network

import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.nepalimsya.sukify.BuildConfig

/**
 * Адрес сервера Sukify. Retrofit собирается один раз с адресом-заглушкой,
 * а настоящий подставляет интерцептор — так сервер можно сменить на экране
 * входа (локальный стенд, другой домен) без пересборки клиента.
 */
@Singleton
class ApiHost @Inject constructor() {

    @Volatile
    private var url: HttpUrl = normalize(BuildConfig.DEFAULT_SERVER)!!

    /** Адрес без завершающего слэша: https://sukify.nepalimsya.ru */
    val base: String get() = url.toString().trimEnd('/')

    val httpUrl: HttpUrl get() = url

    fun update(server: String) {
        normalize(server)?.let { url = it }
    }

    companion object {
        const val PLACEHOLDER = "http://sukify.invalid/api/"

        /** «sukify.ru» → https://sukify.ru, лишние слэши и пути отбрасываем. */
        fun normalize(raw: String): HttpUrl? {
            val text = raw.trim().trimEnd('/')
            if (text.isEmpty()) return null
            val withScheme = if ("://" in text) text else "https://$text"
            val parsed = withScheme.toHttpUrlOrNull() ?: return null
            return parsed.newBuilder().encodedPath("/").query(null).fragment(null).build()
        }
    }
}
