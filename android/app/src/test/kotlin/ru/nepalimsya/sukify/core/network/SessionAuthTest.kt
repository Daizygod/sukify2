package ru.nepalimsya.sukify.core.network

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ru.nepalimsya.sukify.core.datastore.SecretBox
import ru.nepalimsya.sukify.core.datastore.Session
import ru.nepalimsya.sukify.core.datastore.SessionState
import ru.nepalimsya.sukify.core.datastore.SessionStore
import ru.nepalimsya.sukify.data.model.UserDto
import ru.nepalimsya.sukify.di.AppModule

/**
 * Выход из аккаунта по 401. Раньше сверка загрузок раз в 12 ч поднимала
 * процесс в фоне, первые запросы уходили до того, как сессия прочиталась с
 * диска, — без токена, — а пришедший 401 стирал уже загруженную сессию.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SessionAuthTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer()

    /** Без шифрования: здесь проверяется не Keystore. */
    private val box = object : SecretBox {
        override fun seal(plain: ByteArray) = plain

        override fun open(sealed: ByteArray) = sealed
    }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() {
        server.shutdown()
        scope.cancel()
    }

    private fun store(host: ApiHost) = SessionStore(context, box, AppModule.json(), host, scope)

    private fun client(host: ApiHost, store: SessionStore) =
        OkHttpClient.Builder().addInterceptor(ApiInterceptor(host, Provider { store })).build()

    private fun get(client: OkHttpClient) =
        client.newCall(Request.Builder().url("http://placeholder.invalid/api/me").build()).execute().use { it.code }

    @Test
    fun `холодный старт ждёт сессию, а выход — только по 401 на свой токен`() = runBlocking {
        // Вошли когда-то раньше: сессия лежит на диске.
        val before = store(ApiHost())
        withTimeout(5_000) { before.awaitLoaded() }
        before.save(Session(server = server.url("/").toString(), token = "t-1", user = UserDto(id = 1, name = "Тест")))

        // Процесс поднялся заново (фоновая работа): сессия ещё читается, а запрос уже идёт.
        val host = ApiHost()
        val store = store(host)
        val client = client(host, store)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertEquals(200, get(client))
        val first = server.takeRequest()
        assertEquals("Bearer t-1", first.getHeader("Authorization"))
        assertEquals("/api/me", first.path)

        // Отказ на чужой (старый) токен текущую сессию не трогает.
        store.onUnauthorized("t-old")
        assertEquals("t-1", store.current?.token)

        // Отказ на токен этой сессии — его отозвали: выходим.
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertEquals(401, get(client))
        withTimeout(5_000) { store.state.first { it is SessionState.LoggedOut } }

        // Без сессии запросы идут без токена, и 401 на них ничего не ломает.
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertEquals(401, get(client))
        server.takeRequest()
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }
}
