package ru.nepalimsya.sukify.core.network

import android.os.Build
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Только для отладки на эмуляторе: локальный стенд отдаёт ссылки вида
 * http://localhost:9000/… (MinIO), а localhost эмулятора — это сам эмулятор.
 * Компьютер изнутри эмулятора виден как 10.0.2.2 — туда и переводим.
 * На настоящем телефоне не включается (там работает adb reverse).
 */
class EmulatorLoopbackInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (host != "localhost" && host != "127.0.0.1") return chain.proceed(request)
        val url = request.url.newBuilder().host(HOST_LOOPBACK).build()
        return chain.proceed(request.newBuilder().url(url).build())
    }

    companion object {
        const val HOST_LOOPBACK = "10.0.2.2"

        val isEmulator: Boolean by lazy {
            Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
                Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk")
        }
    }
}
