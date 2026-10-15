package ru.nepalimsya.sukify.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.nepalimsya.sukify.di.AppModule

/**
 * Ответы разбираются тем же конвертером, что и в приложении: Retrofit берёт
 * тип из Java-рефлексии, а там нет «?» у аргументов типа.
 */
class SukifyApiDecodingTest {
    private fun api(body: String): SukifyApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("http://localhost/api/")
            .client(client)
            .addConverterFactory(AppModule.json().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SukifyApi::class.java)
    }

    @Test
    fun `сводка переходов — пары без перехода приходят null`() = runBlocking {
        val body = """{"data":{"18:19":{"id":1,"preset":"wave","bars":4,"likes_count":1,"is_preferred":true,"count":2},"19:20":null}}"""
        val map = api(body).transitionSummaries("18:19,19:20").data
        assertEquals("wave", map["18:19"]?.preset)
        assertNull(map["19:20"])
    }

    @Test
    fun `полные переходы для офлайна — пары без перехода приходят null`() = runBlocking {
        val body = """{"data":{"19:20":null}}"""
        val map = api(body).transitionsForContext("19:20").data
        assertNull(map["19:20"])
    }
}
