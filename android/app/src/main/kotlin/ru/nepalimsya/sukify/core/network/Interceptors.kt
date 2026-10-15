package ru.nepalimsya.sukify.core.network

import android.os.Build
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.core.datastore.SessionStore

/**
 * Каждый запрос к API: настоящий адрес сервера вместо заглушки, токен,
 * Accept: application/json (иначе Laravel на ошибках отдаёт HTML) и
 * понятный User-Agent.
 *
 * SessionStore берём через Provider: он сам зависит от ApiHost, и прямое
 * внедрение замкнуло бы граф зависимостей в кольцо.
 */
@Singleton
class ApiInterceptor @Inject constructor(
    private val apiHost: ApiHost,
    private val sessions: Provider<SessionStore>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val base = apiHost.httpUrl

        val url = original.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()

        val request = original.newBuilder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .apply { sessions.get().current?.token?.let { header("Authorization", "Bearer $it") } }
            .build()

        val response = chain.proceed(request)

        // Токен отозвали (отвязали телефон в вебе) или аккаунт заблокирован —
        // выходим. Запросы входа сюда не относятся: там 401/422 — это ответ.
        val isAuthCall = original.url.encodedPath.contains("/mobile/")
        if (!isAuthCall && (response.code == 401 || response.isSuspended())) {
            sessions.get().onUnauthorized()
        }

        return response
    }

    private fun Response.isSuspended(): Boolean =
        code == 403 && peekBody(512).string().contains("suspended", ignoreCase = true)

    companion object {
        val USER_AGENT = "Sukify-Android/${BuildConfig.VERSION_NAME} " +
            "(${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE})"
    }
}
