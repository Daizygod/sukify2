package ru.nepalimsya.sukify.connect

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.PlayContextCommand
import ru.nepalimsya.sukify.playback.PlayerState
import ru.nepalimsya.sukify.playback.queue.RepeatMode

/**
 * Сообщения Sukify Connect в канале `user:playback#{id}` — те же поля, что
 * у stores/devices.js (их же читает настольный компаньон для Discord).
 * Без состояния и Android — проверяется обычными тестами.
 */
object ConnectProtocol {

    fun device(d: ConnectDevice): JsonObject = buildJsonObject {
        put("id", d.id)
        put("name", d.name)
        put("type", d.type)
    }

    /** ping / bye — только кто. */
    fun simple(type: String, me: ConnectDevice): JsonObject = buildJsonObject {
        put("t", type)
        put("d", device(me))
    }

    fun hello(me: ConnectDevice, playing: Boolean): JsonObject = buildJsonObject {
        put("t", "hello")
        put("d", device(me))
        put("playing", playing)
    }

    /** Своё состояние для пультов; null — играть нечего. */
    fun state(me: ConnectDevice, s: PlayerState, positionMs: Long, volume: Float, now: Long): JsonObject? {
        val track = s.track ?: return null
        val q = s.queue
        return buildJsonObject {
            put("t", "state")
            put("d", device(me))
            put("playing", s.playing)
            put("track", lightTrack(track))
            put("pos", positionMs)
            put("dur", s.durationMs.takeIf { it > 0 } ?: track.durationMs)
            put("vol", volume)
            // Очередь для пультов: id контекста после курсора + ручная очередь.
            put("up", buildJsonArray { q.upcoming.take(60).forEach { add(JsonPrimitive(it.id)) } })
            put(
                "man",
                buildJsonArray {
                    q.manual.forEach { m ->
                        add(
                            buildJsonObject {
                                put("q", m.qid)
                                put("id", m.track.id)
                            },
                        )
                    }
                },
            )
            put("ctx", q.contextName)
            put("ctxKey", q.contextKey)
            put("shuffle", q.shuffle)
            put("repeat", q.repeat.wire)
            put("ts", now)
        }
    }

    /** Трек для пульта (lightTrack() в вебе): обложки строками, исполнители одной строкой. */
    fun lightTrack(t: Track): JsonObject = buildJsonObject {
        put("id", t.id)
        put("title", t.title)
        put("artists", t.artistNames)
        put("cover", t.cover?.url(64))
        // Максимальный рендер — для полноэкранного пульта и обложки в Discord.
        put("coverBig", t.cover?.url(1000))
        put("duration_ms", t.durationMs)
        // Альбом и slug пультам не нужны, но компаньон подписывает ими статус в Discord.
        put("album", t.release?.title)
        put("releaseSlug", t.release?.slug)
    }

    /** Снимок очереди для передачи воспроизведения (snapshot() в вебе). */
    fun snapshot(s: PlayerState, positionMs: Long): JsonObject = buildJsonObject {
        put("queueIds", buildJsonArray { s.queue.context.forEach { add(JsonPrimitive(it.id)) } })
        put("manualIds", buildJsonArray { s.queue.manual.forEach { add(JsonPrimitive(it.track.id)) } })
        put("index", s.queue.index)
        put("pos", positionMs)
        put("playing", s.playing)
        put("contextName", s.queue.contextName)
        put("contextKey", s.queue.contextKey)
    }

    fun command(
        me: ConnectDevice,
        to: String,
        action: String,
        now: Long,
        value: JsonElement? = null,
        state: JsonObject? = null,
        target: String? = null,
    ): JsonObject = buildJsonObject {
        put("t", "cmd")
        put("d", device(me))
        put("to", to)
        put("action", action)
        if (value != null) put("value", value)
        if (state != null) put("state", state)
        if (target != null) put("target", target)
        // По нему приёмник отбросит команду, пришедшую из истории канала.
        put("ts", now)
    }

    /** Значение команды движка (send) в JSON. */
    fun toJson(value: Any?): JsonElement? = when (value) {
        null -> null
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is PlayContextCommand -> buildJsonObject {
            put("ids", buildJsonArray { value.ids.forEach { add(JsonPrimitive(it)) } })
            put("index", value.index)
            put("name", value.name)
            put("key", value.key)
        }
        is List<*> -> buildJsonArray { value.forEach { add(toJson(it) ?: JsonNull) } }
        else -> JsonPrimitive(value.toString())
    }

    // --- Разбор ----------------------------------------------------------------------

    fun parseDevice(msg: JsonObject): ConnectDevice? {
        val d = msg["d"] as? JsonObject ?: return null
        val id = d.str("id") ?: return null
        return ConnectDevice(id, d.str("name") ?: "Устройство", d.str("type") ?: "computer", playing = msg.bool("playing") == true)
    }

    fun parseState(msg: JsonObject, device: ConnectDevice, receivedAt: Long): RemoteState = RemoteState(
        device = device,
        playing = msg.bool("playing") == true,
        track = (msg["track"] as? JsonObject)?.let(::parseLight),
        pos = msg.long("pos") ?: 0,
        dur = msg.long("dur") ?: 0,
        vol = msg.double("vol")?.toFloat(),
        up = msg.longs("up"),
        man = (msg["man"] as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val q = o.long("q") ?: return@mapNotNull null
            val id = o.long("id") ?: return@mapNotNull null
            q to id
        }.orEmpty(),
        ctx = msg.str("ctx").orEmpty(),
        ctxKey = msg.str("ctxKey").orEmpty(),
        shuffle = msg.bool("shuffle") == true,
        repeat = RepeatMode.fromWire(msg.str("repeat")),
        receivedAt = receivedAt,
    )

    fun parseLight(o: JsonObject): LightTrack? {
        val id = o.long("id") ?: return null
        return LightTrack(
            id = id,
            title = o.str("title").orEmpty(),
            artists = o.str("artists").orEmpty(),
            cover = o.str("cover"),
            coverBig = o.str("coverBig"),
            durationMs = o.long("duration_ms") ?: 0,
            album = o.str("album"),
            releaseSlug = o.str("releaseSlug"),
        )
    }

    /** Сообщение из истории канала (переподключились) старше минуты — по нему не действуем. */
    fun isStale(msg: JsonObject, now: Long): Boolean {
        val ts = msg.long("ts") ?: return false
        return now - ts > STALE_MS
    }

    const val STALE_MS = 60_000L
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.longs(key: String): List<Long> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.doubleOrNull?.toLong() } }.orEmpty()
