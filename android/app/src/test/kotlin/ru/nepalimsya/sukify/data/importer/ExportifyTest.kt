package ru.nepalimsya.sukify.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportifyTest {
    @Test
    fun `выгрузка Exportify — кавычки, запятые в названиях и несколько исполнителей`() {
        val csv = "\"Track URI\",\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\"\r\n" +
            "\"spotify:track:1\",\"Superhero (Heroes & Villains) [with Future & Chris Brown]\",\"Metro Boomin, Future, Chris Brown\",\"HEROES & VILLAINS\",\"182000\"\r\n" +
            "\"spotify:track:2\",\"Say \"\"Hi\"\", Again\",\"Luna Waves\",\"\",\"\"\n" +
            "\n"
        val items = Exportify.parse(csv)
        assertEquals(2, items.size)
        assertEquals("Superhero (Heroes & Villains) [with Future & Chris Brown]", items[0].title)
        assertEquals(listOf("Metro Boomin", "Future", "Chris Brown"), items[0].artists)
        assertEquals(182_000L, items[0].durationMs)
        assertEquals("Say \"Hi\", Again", items[1].title)
        assertEquals(null, items[1].durationMs)
    }

    @Test
    fun `русские заголовки и чужой CSV`() {
        val ru = "Название трека;Исполнители\n".replace(';', ',') + "Трек,Исполнитель\n"
        assertEquals("Трек", Exportify.parse(ru).single().title)
        assertTrue(Exportify.parse("a,b,c\n1,2,3\n").isEmpty())
    }
}
