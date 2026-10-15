package ru.nepalimsya.sukify.connect

import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.nepalimsya.sukify.domain.ArtistRef
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.domain.ReleaseRef
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.PlayContextCommand
import ru.nepalimsya.sukify.playback.PlayerState
import ru.nepalimsya.sukify.playback.queue.NowPlaying
import ru.nepalimsya.sukify.playback.queue.QueueSnapshot
import ru.nepalimsya.sukify.playback.queue.QueuedTrack
import ru.nepalimsya.sukify.playback.queue.RepeatMode

/**
 * Сообщения Connect против stores/devices.js: веб, телефон и настольный
 * компаньон должны понимать друг друга поле в поле.
 */
class ConnectProtocolTest {
    private val me = ConnectDevice("phone-1", "Sukify • Pixel 7", "phone", playing = false, self = true)

    private fun track(id: Long, title: String) = Track(
        id = id,
        title = title,
        durationMs = 200_000 + id,
        artists = persistentListOf(ArtistRef(1, "Metro Boomin", "metro-boomin"), ArtistRef(2, "Future", "future")),
        release = ReleaseRef(5, "HEROES & VILLAINS", "heroes-villains", "#5a1e1e"),
        cover = Cover(persistentListOf(64 to "c64", 300 to "c300", 1000 to "c1000")),
        streamUrl = "s",
        audioRev = "r",
        loudnessLufs = -9.0,
    )

    private val state = PlayerState(
        queue = QueueSnapshot(
            current = NowPlaying(track(2, "Superhero")),
            context = persistentListOf(track(1, "On Time"), track(2, "Superhero"), track(3, "Too Many Nights"), track(4, "Trance")),
            index = 1,
            manual = persistentListOf(QueuedTrack(7, track(9, "Creepin'"))),
            contextName = "HEROES & VILLAINS",
            contextKey = "release:heroes-villains",
            shuffle = true,
            repeat = RepeatMode.All,
        ),
        playing = true,
        durationMs = 182_000,
    )

    @Test
    fun `state — поля как в broadcastState веба`() {
        val msg = ConnectProtocol.state(me, state, positionMs = 61_500, volume = 0.75f, now = 1_700_000_000_000)!!
        assertEquals(
            setOf("t", "d", "playing", "track", "pos", "dur", "vol", "up", "man", "ctx", "ctxKey", "shuffle", "repeat", "ts"),
            msg.keys,
        )
        assertEquals("state", msg.str("t"))
        assertEquals(mapOf("id" to "phone-1", "name" to "Sukify • Pixel 7", "type" to "phone"), msg.getValue("d").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals(true, msg.bool("playing"))
        assertEquals(61_500L, msg.long("pos"))
        assertEquals(182_000L, msg.long("dur"))
        assertEquals(0.75, msg.double("vol")!!, 1e-6)
        assertEquals(listOf(3L, 4L), msg.longs("up"))
        val man = msg.getValue("man").jsonArray.single().jsonObject
        assertEquals(7L, man.long("q"))
        assertEquals(9L, man.long("id"))
        assertEquals("HEROES & VILLAINS", msg.str("ctx"))
        assertEquals("release:heroes-villains", msg.str("ctxKey"))
        assertEquals(true, msg.bool("shuffle"))
        assertEquals("all", msg.str("repeat"))
        assertEquals(1_700_000_000_000L, msg.long("ts"))
    }

    @Test
    fun `трек для пульта и компаньона — lightTrack`() {
        val t = ConnectProtocol.lightTrack(track(2, "Superhero"))
        assertEquals(setOf("id", "title", "artists", "cover", "coverBig", "duration_ms", "album", "releaseSlug"), t.keys)
        assertEquals("Metro Boomin, Future", t.str("artists"))
        assertEquals("c64", t.str("cover"))
        assertEquals("c1000", t.str("coverBig"))
        assertEquals(200_002L, t.long("duration_ms"))
        assertEquals("HEROES & VILLAINS", t.str("album"))
        assertEquals("heroes-villains", t.str("releaseSlug"))
    }

    @Test
    fun `нечего играть — state не шлём`() {
        assertNull(ConnectProtocol.state(me, PlayerState(), 0, 0.5f, 0))
    }

    @Test
    fun `состояние веба разбирается — как его шлёт devices_js`() {
        // Ровно то, что публикует broadcastState() во вкладке браузера (pos — дробный).
        val web = Json.parseToJsonElement(
            """
            {"t":"state","d":{"id":"tab-1","name":"Chrome • Windows","type":"computer"},"playing":true,
             "track":{"id":19,"title":"Willow","artists":"The Paper Kites","cover":"https://x/64.webp","coverBig":"https://x/1000.webp",
                      "duration_ms":21000,"album":"twelvefour","releaseSlug":"twelvefour"},
             "pos":3012.6,"dur":21000,"vol":0.5,"up":[20,21],"man":[{"q":3,"id":22}],
             "ctx":"Тест микса","ctxKey":"playlist:2","shuffle":false,"repeat":"one","ts":1700000000000}
            """,
        ).jsonObject
        val device = ConnectProtocol.parseDevice(web)!!
        assertEquals("tab-1", device.id)
        assertEquals("Chrome • Windows", device.name)
        assertEquals("computer", device.type)
        assertTrue(device.playing)
        val r = ConnectProtocol.parseState(web, device, receivedAt = 42)
        assertEquals(19L, r.track?.id)
        assertEquals("Willow", r.track?.title)
        assertEquals("https://x/1000.webp", r.track?.coverBig)
        assertEquals(3012L, r.pos)
        assertEquals(21_000L, r.dur)
        assertEquals(0.5f, r.vol)
        assertEquals(listOf(20L, 21L), r.up)
        assertEquals(listOf(3L to 22L), r.man)
        assertEquals("playlist:2", r.ctxKey)
        assertEquals(RepeatMode.One, r.repeat)
        assertEquals(42L, r.receivedAt)
    }

    @Test
    fun `снимок для передачи — snapshot веба`() {
        val snap = ConnectProtocol.snapshot(state, positionMs = 12_345)
        assertEquals(setOf("queueIds", "manualIds", "index", "pos", "playing", "contextName", "contextKey"), snap.keys)
        assertEquals(listOf(1L, 2L, 3L, 4L), snap.longs("queueIds"))
        assertEquals(listOf(9L), snap.longs("manualIds"))
        assertEquals(1L, snap.long("index"))
        assertEquals(12_345L, snap.long("pos"))
    }

    @Test
    fun `команды — t, d, to, action и метка времени`() {
        val play = ConnectProtocol.command(
            me, to = "tab-1", action = "play-context", now = 5,
            value = ConnectProtocol.toJson(PlayContextCommand(listOf(1, 2), 1, "Альбом", "release:x")),
        )
        assertEquals(setOf("t", "d", "to", "action", "value", "ts"), play.keys)
        val v = play.getValue("value").jsonObject
        assertEquals(listOf(1L, 2L), v.longs("ids"))
        assertEquals(1L, v.long("index"))
        assertEquals("release:x", v.str("key"))

        val pull = ConnectProtocol.command(me, to = "tab-1", action = "push-transfer", now = 5, target = "phone-1")
        assertEquals("phone-1", pull.str("target"))
        assertFalse("value" in pull)

        val queue = ConnectProtocol.command(me, "tab-1", "queue-add", 5, value = ConnectProtocol.toJson(listOf(7L)))
        assertEquals(listOf(7L), queue.longs("value"))
    }

    @Test
    fun `сообщения из истории старше минуты — протухшие`() {
        fun msg(ts: Long?) = JsonObject(if (ts == null) emptyMap() else mapOf("ts" to kotlinx.serialization.json.JsonPrimitive(ts)))
        assertFalse(ConnectProtocol.isStale(msg(null), 1_000_000))
        assertFalse(ConnectProtocol.isStale(msg(1_000_000 - 59_000), 1_000_000))
        assertTrue(ConnectProtocol.isStale(msg(1_000_000 - 61_000), 1_000_000))
    }
}
