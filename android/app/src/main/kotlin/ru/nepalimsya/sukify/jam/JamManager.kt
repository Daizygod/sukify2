package ru.nepalimsya.sukify.jam

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.connect.bool
import ru.nepalimsya.sukify.connect.long
import ru.nepalimsya.sukify.connect.longs
import ru.nepalimsya.sukify.connect.str
import ru.nepalimsya.sukify.core.datastore.SessionStore
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.core.network.apiError
import ru.nepalimsya.sukify.core.realtime.Realtime
import ru.nepalimsya.sukify.core.realtime.RealtimeChannel
import ru.nepalimsya.sukify.data.model.JamSessionDto
import ru.nepalimsya.sukify.data.model.UserDto
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.PlaybackEngine
import ru.nepalimsya.sukify.playback.PlayerState
import ru.nepalimsya.sukify.ui.components.Toasts

/** Джем для экранов: код, хост, участники и есть ли связь с комнатой. */
@Immutable
data class JamUi(
    val id: Long,
    val code: String,
    val host: UserDto?,
    val members: List<UserDto>,
    val isHost: Boolean,
    val connected: Boolean,
)

/**
 * Джем — совместное прослушивание (порт stores/jam.js): хост транслирует
 * своё состояние в канал `session:{id}`, участники синхронно играют то же
 * самое у себя. Переключать и ставить на паузу может любой.
 *
 * Гость подписывается уже после того, как сервер разослал member.joined,
 * поэтому сам здоровается (jam-hello) и читает историю канала. Джем
 * переживает перезапуск приложения.
 */
@Singleton
class JamManager @Inject constructor(
    @ApplicationContext context: Context,
    private val api: SukifyApi,
    private val realtime: Realtime,
    private val engine: PlaybackEngine,
    private val sessions: SessionStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val _session = MutableStateFlow<JamSessionDto?>(null)
    private val _connected = MutableStateFlow(false)
    private val _ui = MutableStateFlow<JamUi?>(null)

    /** null — не в джеме. */
    val ui: StateFlow<JamUi?> = _ui.asStateFlow()

    private var channel: RealtimeChannel? = null
    private val jobs = mutableListOf<Job>()

    /** Пока применяем чужое состояние, своё не шлём. */
    private var applying = false

    /** Подпись последнего принятого снимка — эхо своего же не отправляем. */
    private var lastApplied = ""
    private var lastErrorToast = 0L

    private val myId: Long? get() = sessions.current?.user?.id

    private val isHost: Boolean
        get() = _session.value?.let { s -> (s.host?.id ?: s.hostUserId) == myId } == true

    private fun publishUi() {
        val s = _session.value
        _ui.value = s?.let { JamUi(it.id, it.joinCode, it.host, it.members, isHost, _connected.value) }
    }

    // --- Вход и выход ---------------------------------------------------------------

    suspend fun start(): Boolean = apiCall { api.jamStart().data }
        .onSuccess {
            enter(it)
            Toasts.show("Джем создан! Код: ${it.joinCode}")
        }
        .onFailure { Toasts.show("Не получилось создать джем") }
        .isSuccess

    suspend fun join(code: String): Boolean = apiCall { api.jamJoin(buildJsonObject { put("join_code", code.trim()) }).data }
        .onSuccess {
            enter(it)
            Toasts.show("Ты в джеме — сейчас подхватим, что играет у хоста")
        }
        .onFailure { Toasts.show("Джем с таким кодом не найден") }
        .isSuccess

    /** После перезапуска приложения джем должен остаться. */
    suspend fun restore() {
        val id = prefs.getLong(KEY_SESSION, 0).takeIf { it > 0 } ?: return
        if (_session.value != null) return
        apiCall { api.jamSession(id).data }
            .onSuccess { if (it.isActive) enter(it) else remember(null) }
            // Без сети — не забываем: попробуем при следующем запуске.
            .onFailure { if ((it.apiError.code ?: 0) in 400..499) remember(null) }
    }

    /** Гость выходит, хост завершает джем для всех. */
    suspend fun leave() {
        val s = _session.value ?: return
        val wasHost = isHost
        apiCall { if (wasHost) api.jamEnd(s.id) else api.jamLeave(s.id) }
        cleanup()
        Toasts.show(if (wasHost) "Джем завершён" else "Ты вышел из джема")
    }

    /** Выход из аккаунта: просто забываем, сервер сам уберёт по таймауту. */
    suspend fun forget() = cleanup()

    private suspend fun enter(data: JamSessionDto) {
        if (_session.value != null) cleanup()
        _session.value = data
        remember(data.id)
        publishUi()
        openChannel()
        startBroadcast()
    }

    private suspend fun cleanup() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        channel?.let { realtime.closeChannel(it) }
        channel = null
        _connected.value = false
        _session.value = null
        lastApplied = ""
        remember(null)
        publishUi()
    }

    private fun remember(id: Long?) {
        prefs.edit().apply { if (id != null) putLong(KEY_SESSION, id) else remove(KEY_SESSION) }.apply()
    }

    // --- Канал ----------------------------------------------------------------------

    private suspend fun openChannel() {
        val s = _session.value ?: return
        val name = s.channel ?: "session:${s.id}"
        val ch = realtime.openChannel(name) {
            api.subscriptionToken(buildJsonObject { put("channel", name) }).token
        }
        if (ch == null) {
            // Нет подключения (без сети на старте) — откроем, когда появится.
            jobs += scope.launch {
                realtime.connected.first { it }
                openChannel()
            }
            return
        }
        channel = ch
        jobs += scope.launch { ch.events.collect { handle(it) } }
        jobs += scope.launch {
            ch.subscribed.collect {
                _connected.value = it
                publishUi()
            }
        }
        jobs += scope.launch { ch.joins.collect { onSubscribed() } }
        jobs += scope.launch {
            ch.errors.collect {
                // Молчать нельзя: снаружи это выглядит как «джем есть, музыки нет».
                val now = SystemClock.elapsedRealtime()
                if (now - lastErrorToast > 30_000) {
                    lastErrorToast = now
                    Toasts.show("Джем не может подключиться к серверу — музыка не синхронизируется")
                }
            }
        }
    }

    /** Подписались: хост сразу шлёт снимок, гость смотрит историю и просит снимок. */
    private suspend fun onSubscribed() {
        if (isHost) {
            pushState()
            return
        }
        val last = channel?.history(20)?.firstOrNull { (it.str("t") ?: it.str("type")) == "jam-state" }
        if (last != null) applyState(last)
        channel?.publish(
            buildJsonObject {
                put("t", "jam-hello")
                put("from", myId)
            },
        )
    }

    private fun startBroadcast() {
        // Мерное «играю вот это» шлёт только хост: если так делать каждому,
        // участники начнут перетягивать позицию друг у друга.
        jobs += scope.launch {
            while (true) {
                delay(STATE_INTERVAL_MS)
                if (isHost) pushState()
            }
        }
        // А нажатия — от кого угодно. Перемотку ловим по счётчику, очередь — по отпечатку.
        jobs += scope.launch {
            engine.localState
                .map { s -> "${s.track?.id}:${s.playing}:${s.seekTick}:${queueKey(s)}" }
                .distinctUntilChanged()
                .drop(1)
                .collect { pushState() }
        }
    }

    private fun pushState() {
        if (_session.value == null || applying) return
        val s = engine.localState.value
        val me = myId ?: return
        val msg = JamProtocol.state(me, s, engine.localPositionMs(), engine.shuffleSource()) ?: return
        // Только что применили чужое — своё точно такое же не шлём, иначе двое зациклятся.
        if (JamProtocol.signature(msg) == lastApplied) return
        channel?.publish(msg)
    }

    private suspend fun handle(msg: JsonObject) {
        when (msg.str("t") ?: msg.str("type")) {
            "member.joined" -> {
                val user = msg["user"] as? JsonObject
                if (user?.long("id") != myId) Toasts.show("${user?.str("name") ?: "Кто-то"} присоединился к джему")
                refreshMembers()
                if (isHost) pushState()
            }
            "member.left" -> refreshMembers()
            "session.ended" -> {
                cleanup()
                Toasts.show("Джем завершён")
            }
            // Гость поздоровался — хост отвечает снимком, не дожидаясь такта.
            "jam-hello" -> if (isHost && msg.long("from") != myId) pushState()
            "jam-state" -> if (msg.long("from") != myId) applyState(msg)
        }
    }

    /** Участники — с сервера: он и есть источник правды. */
    private suspend fun refreshMembers() {
        val s = _session.value ?: return
        apiCall { api.jamSession(s.id).data }.onSuccess {
            if (_session.value?.id == it.id) {
                _session.value = it
                publishUi()
            }
        }
    }

    /** Повторяем то, что включил другой участник. */
    private suspend fun applyState(m: JsonObject) {
        if (applying) return
        applying = true
        lastApplied = JamProtocol.signature(m)
        try {
            val trackId = m.long("trackId") ?: return
            val queueIds = m.longs("queueIds").ifEmpty { listOf(trackId) }
            val manualIds = m.longs("manualIds")
            val index = (m.long("index") ?: 0).toInt()
            val shuffle = m.bool("shuffle")
            val pos = m.long("pos") ?: 0
            val playing = m.bool("playing") == true
            val local = engine.localState.value

            if (local.track?.id == trackId) {
                // Трек тот же, но очередь могли перетасовать или дополнить — подтягиваем её, не трогая звук.
                val theirs = listOf(queueIds.joinToString("."), manualIds.joinToString("."), index, if (shuffle == true) 1 else 0).joinToString("|")
                if (queueKey(local) != theirs) {
                    val tracks = engine.fetchTracks(queueIds)
                    val manual = engine.fetchTracks(manualIds)
                    engine.local { applyQueueSnapshot(tracks, index, manual, shuffle, orderOf(m.longs("sourceIds"), tracks)) }
                }
                // Позицию подтягиваем при рассинхроне больше трёх секунд.
                if (abs(engine.localPositionMs() - pos) > 3_000) engine.local { seekTo(pos) }
                if (playing != engine.localState.value.playing) engine.local { togglePlay() }
                return
            }
            val tracks = engine.fetchTracks(queueIds)
            if (tracks.isEmpty()) return
            val manual = engine.fetchTracks(manualIds)
            engine.local {
                hydrate(
                    tracks = tracks,
                    index = tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0),
                    manual = manual,
                    positionMs = pos,
                    play = playing,
                    name = "Джем: ${m.str("contextName").orEmpty()}",
                    key = "",
                )
                applyQueueSnapshot(null, null, null, shuffle, orderOf(m.longs("sourceIds"), tracks))
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "не удалось повторить состояние хоста", e)
        } finally {
            applying = false
        }
    }

    /** Порядок до перемешивания — из уже полученных треков, второй запрос не нужен. */
    private fun orderOf(ids: List<Long>, tracks: List<Track>): List<Track>? {
        if (ids.isEmpty() || tracks.isEmpty()) return null
        val byId = tracks.associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    private fun queueKey(s: PlayerState): String = JamProtocol.queueKey(s)

    private companion object {
        const val TAG = "SukifyJam"
        const val PREFS = "jam"
        const val KEY_SESSION = "session"
        const val STATE_INTERVAL_MS = 5_000L
    }
}

/** Сообщения джема — как в stores/jam.js. */
object JamProtocol {
    fun state(from: Long, s: PlayerState, positionMs: Long, source: List<Track>): JsonObject? {
        val track = s.track ?: return null
        val q = s.queue
        return buildJsonObject {
            put("t", "jam-state")
            put("from", from)
            put("trackId", track.id)
            put("queueIds", buildJsonArray { q.context.take(300).forEach { add(JsonPrimitive(it.id)) } })
            // Ручная очередь отдельно: она играет раньше контекста.
            put("manualIds", buildJsonArray { q.manual.take(100).forEach { add(JsonPrimitive(it.track.id)) } })
            put("index", q.index)
            put("shuffle", q.shuffle)
            // Порядок до перемешивания: без него выключить «вперемешку» смог бы только включивший.
            put("sourceIds", buildJsonArray { source.take(300).forEach { add(JsonPrimitive(it.id)) } })
            put("pos", positionMs)
            put("playing", s.playing)
            put("contextName", q.contextName)
        }
    }

    /** Подпись снимка: отличаем «мы это только что применили» от своего. */
    fun signature(m: JsonObject): String = listOf(
        m.long("trackId") ?: 0,
        if (m.bool("playing") == true) 1 else 0,
        ((m.long("pos") ?: 0) / 2000.0).roundToLong(),
        if (m.bool("shuffle") == true) 1 else 0,
        m.long("index") ?: 0,
        m.longs("queueIds").joinToString("."),
        m.longs("manualIds").joinToString("."),
    ).joinToString(":")

    /** Отпечаток очереди: порядок, ручная очередь, позиция и перемешивание. */
    fun queueKey(s: PlayerState): String = listOf(
        s.queue.context.joinToString(".") { it.id.toString() },
        s.queue.manual.joinToString(".") { it.track.id.toString() },
        s.queue.index,
        if (s.queue.shuffle) 1 else 0,
    ).joinToString("|")
}
