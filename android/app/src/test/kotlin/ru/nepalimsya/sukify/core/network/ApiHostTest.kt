package ru.nepalimsya.sukify.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiHostTest {

    @Test
    fun normalizesWhatPeopleType() {
        assertEquals("https://sukify.nepalimsya.ru/", ApiHost.normalize("sukify.nepalimsya.ru")?.toString())
        assertEquals("https://sukify.nepalimsya.ru/", ApiHost.normalize(" https://sukify.nepalimsya.ru/ ")?.toString())
        assertEquals("http://10.0.2.2:8088/", ApiHost.normalize("http://10.0.2.2:8088/api")?.toString())
        assertEquals("http://192.168.1.5:8088/", ApiHost.normalize("http://192.168.1.5:8088")?.toString())
        assertNull(ApiHost.normalize("   "))
    }
}
