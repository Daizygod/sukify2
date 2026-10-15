package ru.nepalimsya.sukify.data.model

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.nepalimsya.sukify.di.AppModule

class MixDtosTest {
    private val json = AppModule.json()

    @Test
    fun `сводка переходов пар разбирается вместе с пустыми парами`() {
        val body = """{"data":{"18:19":{"id":1,"preset":"wave","bars":4,"likes_count":1,"is_preferred":true,"count":2},"19:20":null}}"""
        val serializer = DataEnvelope.serializer(MapSerializer(String.serializer(), TransitionSummaryDto.serializer().nullable))
        val map = json.decodeFromString(serializer, body).data
        assertEquals("wave", map.getValue("18:19")?.preset)
        assertEquals(true, map.getValue("18:19")?.isPreferred)
        assertNull(map["19:20"])
    }
}
