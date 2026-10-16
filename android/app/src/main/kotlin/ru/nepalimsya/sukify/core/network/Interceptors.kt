package ru.nepalimsya.sukify.core.network

import android.os.Build
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Response
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.core.datastore.SessionState
import ru.nepalimsya.sukify.core.datastore.SessionStore
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

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
        val store = sessions.get()
        // Холодный старт в фоне (сверка загрузок раз в 12 ч, виджет, машина):
        // сессия и адрес сервера ещё читаются с диска. Запрос без токена получил
        // бы 401 — ждём их.
        if (store.state.value is SessionState.Loading) {
            runBlocking { withTimeoutOrNull(SESSION_WAIT_MS) { store.awaitLoaded() } }
        }
        val token = store.current?.token
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
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()

        val response = chain.proceed(request)

        // Токен отозвали (отвязали телефон в вебе) или аккаунт заблокирован —
        // выходим, но только если отказ пришёл именно на токен этой сессии.
        // Запросы входа сюда не относятся: там 401/422 — это ответ.
        val isAuthCall = original.url.encodedPath.contains("/mobile/")
        if (!isAuthCall && token != null && (response.code == 401 || response.isSuspended())) {
            store.onUnauthorized(token)
        }

        return response
    }

    private fun Response.isSuspended(): Boolean =
        code == 403 && peekBody(512).string().contains("suspended", ignoreCase = true)

    companion object {
        private const val SESSION_WAIT_MS = 5_000L

        val USER_AGENT = "Sukify-Android/${BuildConfig.VERSION_NAME} " +
            "(${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE})"
    }
}
