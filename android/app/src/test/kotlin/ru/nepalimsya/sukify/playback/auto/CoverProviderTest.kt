package ru.nepalimsya.sukify.playback.auto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Провайдер обложек открыт наружу (машине) — отдаёт только подписанные нами адреса. */
class CoverProviderTest {

    private val key = ByteArray(32) { it.toByte() }
    private val url = "https://s3.timeweb.cloud/sukify/covers/12/300.webp?v=1791985012"

    @Test
    fun `свой адрес проходит`() {
        assertTrue(CoverProvider.verify(key, url, CoverProvider.sign(key, url)))
    }

    @Test
    fun `чужой или подменённый адрес — нет`() {
        val signature = CoverProvider.sign(key, url)
        assertFalse(CoverProvider.verify(key, "http://example.com/covers/12/300.webp", signature))
        assertFalse(CoverProvider.verify(key, url.replace("12", "13"), signature))
        assertFalse(CoverProvider.verify(key, url, null))
        assertFalse(CoverProvider.verify(key, url, ""))
        assertFalse(CoverProvider.verify(ByteArray(32), url, signature))
    }
}
