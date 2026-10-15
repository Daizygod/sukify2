package ru.nepalimsya.sukify.connect

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.core.realtime.Realtime
import ru.nepalimsya.sukify.domain.ArtistRef
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.domain.ReleaseRef
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.PlaybackEngine
import ru.nepalimsya.sukify.playback.PlayerState
import ru.nepalimsya.sukify.playback.RemoteControl
import ru.nepalimsya.sukify.playback.queue.NowPlaying
import ru.nepalimsya.sukify.playback.queue.QueueSnapshot
import ru.nepalimsya.sukify.playback.queue.QueuedTrack
import ru.nepalimsya.sukify.playback.queue.RepeatMode
import ru.nepalimsya.sukify.ui.components.Toasts

/** Устройство в списке Connect. */
@Immutable
data class ConnectDevice(
    val id: String,
    val name: String,
    val type: String,
    val playing: Boolean,
    val self: Boolean = false,
)

/** Трек из трансляции другого устройства — облегчённый, как lightTrack() в вебе. */
@Immutable
data class LightTrack(
    val id: Long,
    val title: String,
    val artists: String,
    val cover: String?,
    val coverBig: String?,
    val durationMs: Long,
    val album: String?,
    val releaseSlug: String?,
)

/** Последняя трансляция активного устройства. */
@Immutable
data class RemoteState(
    val device: ConnectDevice,
    val playing: Boolean,
    val track: LightTrack?,
    val pos: Long,
    val dur: Long,
    val vol: Float?,
    val up: List<Long>,
    /** Ручная очередь: qid на том устройстве → id трека. */
    val man: List<Pair<Long, Long>>,
    val ctx: String,
    val ctxKey: String,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    /** Когда получили (elapsedRealtime) — от него тикает позиция между трансляциями. */
    val receivedAt: Long,
)

/**
 * Sukify Connect — порт stores/devices.js без отличий в протоколе. Каждое
 * открытое приложение или вкладка — «устройство» в личном канале
 * пользователя: устройства представляются (hello), играющее транслирует
 * своё состояние (state), любое может слать команды (cmd), в том числе
 * «перенести воспроизведение сюда/туда».
 *
 * Пока активно другое устройство, этот класс — пульт для PlaybackEngine:
 * команды плеера уходят туда, а экраны показывают его состояние. Телефон
 * виден в списке, пока приложение открыто или играет.
 */
@Singleton
class ConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val realtime: Realtime,
    private val engine: PlaybackEngine,
) : RemoteControl {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(AudioManager::class.java)

    val myId: String by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { prefs ->
            prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
        }
    }
    val myName: String = "Sukify • ${deviceName()}"
    private val me by lazy { ConnectDevice(myId, myName, TYPE_PHONE, playing = false, self = true) }

    private val others = LinkedHashMap<String, Pair<ConnectDevice, Long>>()

    private val _devices = MutableStateFlow(listOf(me))

    /** Это устройство первым, за ним — те, кто отзывался последние 30 секунд. */
    val devices: StateFlow<List<ConnectDevice>> = _devices.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _remote = MutableStateFlow<RemoteState?>(null)

    /** Состояние активного устройства, пока это не мы. */
    val remote: StateFlow<RemoteState?> = _remote.asStateFlow()

    private val trackCache = LruCache<Long, Track>(400)
    private var resolveJob: Job? = null
    private var remoteView: PlayerState? = null

    private var jobs = mutableListOf<Job>()
    private var foreground = false
    private var present = false

    /** Выполняем пришедшую сюда команду — она для своего звука, не пульт. */
    private var executing = false

    // --- RemoteControl -------------------------------------------------------------

    override val active: Boolean
        get() {
            val r = _remote.value ?: return false
            val id = _activeId.value ?: return false
            return !executing && id != myId && SystemClock.elapsedRealtime() - r.receivedAt < DEVICE_TTL_MS
        }

    override val view: PlayerState? get() = remoteView

    override fun positionMs(): Long {
        val r = _remote.value ?: return 0
        val drift = if (r.playing) SystemClock.elapsedRealtime() - r.receivedAt else 0
        val pos = r.pos + drift
        return if (r.dur > 0) pos.coerceAtMost(r.dur) else pos
    }

    override fun send(action: String, value: Any?) = sendCommand(action, value)

    // --- Жизненный цикл ------------------------------------------------------------

    fun start() {
        if (jobs.isNotEmpty()) return
        engine.remote = this
        jobs += scope.launch { realtime.events.collect(::handleMessage) }
        jobs += scope.launch {
            realtime.subscribed.collect {
                if (present) {
                    publish("ping")
                    sayHello()
                }
            }
        }
        jobs += scope.launch { watchPlayer() }
        jobs += scope.launch {
            while (true) {
                delay(HELLO_INTERVAL_MS)
                if (present) sayHello()
            }
        }
        jobs += scope.launch {
            while (true) {
                delay(STATE_INTERVAL_MS)
                // Транслируем и на паузе: иначе через 30 секунд пульты «слепнут».
                if (present && _activeId.value == myId && engine.localState.value.track != null) broadcastState()
                pruneDevices()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycle)
    }

    /** Выход из аккаунта: прощаемся и забываем чужие устройства. */
    fun stop() {
        if (present) publish("bye")
        present = false
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycle)
        jobs.forEach { it.cancel() }
        jobs.clear()
        others.clear()
        _activeId.value = null
        setRemote(null)
        publishDevices()
        engine.remote = null
    }

    private val lifecycle = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            foreground = true
            updatePresence()
        }

        override fun onStop(owner: LifecycleOwner) {
            foreground = false
            updatePresence()
        }
    }

    /** В списке устройств телефон, пока приложение открыто или играет. */
    private fun updatePresence() {
        val should = foreground || engine.localState.value.playing
        if (should == present) return
        present = should
        if (should) {
            publish("ping")
            sayHello()
            if (_activeId.value == myId && engine.localState.value.track != null) broadcastState()
        } else {
            publish("bye")
        }
    }

    // --- Отправка ------------------------------------------------------------------

    private fun sayHello() = realtime.publish(ConnectProtocol.hello(me, engine.localState.value.playing))

    private fun publish(type: String) = realtime.publish(ConnectProtocol.simple(type, me))

    private var broadcastJob: Job? = null

    /**
     * Своё состояние — пультам и настольному компаньону (статус в Discord).
     * Вызовы в одном такте главного потока (команда и её же наблюдатель)
     * склеиваются в одну трансляцию — с самым свежим состоянием.
     */
    fun broadcastState() {
        if (broadcastJob?.isActive == true) return
        broadcastJob = scope.launch {
            yield()
            sendState()
        }
    }

    private fun sendState() {
        val message = ConnectProtocol.state(me, engine.localState.value, engine.localPositionMs(), systemVolume(), System.currentTimeMillis())
        message?.let(realtime::publish)
    }

    // --- Приём ---------------------------------------------------------------------

    private fun handleMessage(msg: JsonObject) {
        val type = msg.str("t") ?: return
        val dev = ConnectProtocol.parseDevice(msg)
        // Свои публикации возвращаются эхом — пропускаем.
        if (dev?.id == myId) return
        // Сообщения из истории канала (переподключились) старше минуты — не действуем по ним.
        val stale = ConnectProtocol.isStale(msg, System.currentTimeMillis())
        if (dev != null && !stale) {
            others[dev.id] = dev to SystemClock.elapsedRealtime()
            publishDevices()
        }

        when (type) {
            "ping" -> {
                if (present) {
                    sayHello()
                    if (_activeId.value == myId && engine.localState.value.track != null) broadcastState()
                }
            }
            "bye" -> {
                if (dev == null) return
                others.remove(dev.id)
                publishDevices()
                if (_activeId.value == dev.id) {
                    _activeId.value = null
                    setRemote(null)
                }
            }
            "state" -> if (dev != null && !stale) onRemoteState(msg, dev)
            "cmd" -> if (msg.str("to") == myId && !stale) {
                scope.launch { execCommand(msg) }
            }
        }
    }

    private fun onRemoteState(msg: JsonObject, dev: ConnectDevice) {
        val state = ConnectProtocol.parseState(msg, dev, SystemClock.elapsedRealtime())
        if (state.playing) {
            _activeId.value = dev.id
            // Активное устройство одно: заиграло другое — мы плавно замолкаем.
            if (engine.localState.value.playing) {
                engine.fadeOutAndPause(1_100)
                // Сами только что отдали туда — тост уже был.
                val pushed = pushedTo
                if (pushed == null || pushed.first != dev.id || SystemClock.elapsedRealtime() - pushed.second > 10_000) {
                    Toasts.show("Играет на устройстве «${dev.name}»")
                }
            }
        } else if (_activeId.value == null || _activeId.value == dev.id) {
            // Активное устройство на паузе — оно всё ещё «активное» (как в Spotify).
            _activeId.value = dev.id
        }
        if (_activeId.value == dev.id) setRemote(state)
    }

    // --- Команды сюда ----------------------------------------------------------------

    private suspend fun execCommand(msg: JsonObject) {
        val value = msg["value"]
        debug("cmd ${msg.str("action")} $value")
        executing = true
        try {
            when (msg.str("action")) {
                "play" -> if (!engine.localState.value.playing) engine.local { play() }
                "pause" -> engine.pauseLocal()
                "next" -> engine.local { next() }
                "prev" -> engine.local { previous() }
                "seek" -> {
                    (value as? JsonPrimitive)?.doubleOrNull?.let { ms -> engine.local { seekTo(ms.toLong()) } }
                    broadcastState()
                }
                "volume" -> {
                    (value as? JsonPrimitive)?.doubleOrNull?.let { setSystemVolume(it.toFloat()) }
                    broadcastState()
                }
                "transfer" -> receiveTransfer(msg["state"] as? JsonObject)
                "queue-add" -> {
                    val ids = when (value) {
                        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
                        is JsonPrimitive -> listOfNotNull(value.longOrNull ?: value.doubleOrNull?.toLong())
                        else -> emptyList()
                    }
                    engine.fetchTracks(ids).forEach { t -> engine.local { addToQueue(t) } }
                    broadcastState()
                }
                "queue-remove-manual" -> {
                    (value as? JsonPrimitive)?.longOrNull?.let { engine.local { removeFromManual(it) } }
                    broadcastState()
                }
                "queue-remove-upcoming" -> {
                    (value as? JsonPrimitive)?.longOrNull?.let { engine.local { removeUpcoming(it.toInt()) } }
                    broadcastState()
                }
                "play-manual" -> {
                    (value as? JsonPrimitive)?.longOrNull?.let { engine.local { playManual(it) } }
                    broadcastState()
                }
                "play-upcoming" -> {
                    (value as? JsonPrimitive)?.longOrNull?.let { engine.local { playUpcoming(it.toInt()) } }
                    broadcastState()
                }
                // Другое устройство просит передать воспроизведение на target.
                "push-transfer" -> msg.str("target")?.let { target ->
                    // Не глушим сразу: играем, пока приёмник не заиграет (его state запустит затухание).
                    publishCommand(target, "transfer", state = snapshotJson())
                }
                "play-context" -> {
                    val o = value as? JsonObject ?: return
                    val ids = o.longs("ids")
                    val tracks = engine.fetchTracks(ids)
                    if (tracks.isNotEmpty()) {
                        val index = (o.long("index") ?: 0).toInt().coerceIn(0, tracks.size - 1)
                        engine.local { playContext(tracks, tracks[index], o.str("name").orEmpty(), o.str("key").orEmpty()) }
                    }
                    broadcastState()
                }
                "shuffle" -> {
                    engine.local { setShuffle((value as? JsonPrimitive)?.booleanOrNull == true) }
                    broadcastState()
                }
                "repeat" -> {
                    engine.local { setRepeat(RepeatMode.fromWire((value as? JsonPrimitive)?.contentOrNull)) }
                    broadcastState()
                }
            }
        } finally {
            executing = false
        }
    }

    /** Это устройство выбрали в списке на другом — забираем воспроизведение. */
    private suspend fun receiveTransfer(state: JsonObject?) {
        state ?: return
        val queueIds = state.longs("queueIds")
        val manualIds = state.longs("manualIds")
        val byId = engine.fetchTracks((queueIds + manualIds).distinct()).associateBy { it.id }
        val tracks = queueIds.mapNotNull(byId::get)
        if (tracks.isEmpty()) {
            Toasts.show("Не удалось перенести воспроизведение")
            return
        }
        // Индекс считали по полной очереди отправителя — сдвигаем на выпавшие треки.
        val index = (state.long("index") ?: 0).toInt()
        val target = queueIds.getOrNull(index)
        val localIndex = tracks.indexOfFirst { it.id == target }.takeIf { it >= 0 } ?: index.coerceIn(0, tracks.size - 1)
        engine.local {
            hydrate(
                tracks = tracks,
                index = localIndex,
                manual = manualIds.mapNotNull(byId::get),
                positionMs = state.long("pos") ?: 0,
                play = state.bool("playing") != false,
                name = state.str("contextName").orEmpty(),
                key = state.str("contextKey").orEmpty(),
            )
        }
        _activeId.value = myId
        setRemote(null)
        broadcastState()
        Toasts.show("Воспроизведение перенесено сюда")
    }

    private fun snapshotJson(): JsonObject = ConnectProtocol.snapshot(engine.localState.value, engine.localPositionMs())

    // --- Пульт ---------------------------------------------------------------------

    private var pushedTo: Pair<String, Long>? = null

    /** Тап по устройству в списке: забрать воспроизведение сюда или отдать туда. */
    fun transferTo(deviceId: String) {
        if (deviceId == myId) {
            // Просим играющее устройство передать нам свой снимок.
            val from = _activeId.value
            if (!active || from == null) return
            publishCommand(from, "push-transfer", target = myId)
            return
        }
        if (engine.localState.value.track == null) {
            Toasts.show("Сначала включи что-нибудь здесь")
            return
        }
        publishCommand(deviceId, "transfer", state = snapshotJson())
        pushedTo = deviceId to SystemClock.elapsedRealtime()
        // Играем дальше, пока приёмник не пришлёт state, — тогда плавно затихнем.
        val name = others[deviceId]?.first?.name ?: "устройство"
        Toasts.show("Играет на устройстве «$name»")
    }

    private fun publishCommand(to: String, action: String, state: JsonObject? = null, value: JsonElement? = null, target: String? = null) {
        realtime.publish(ConnectProtocol.command(me, to, action, System.currentTimeMillis(), value, state, target))
    }

    /** Команда активному устройству и сразу — оптимистично — в его состояние на экране. */
    fun sendCommand(action: String, value: Any? = null) {
        val to = _activeId.value ?: return
        if (to == myId) return
        publishCommand(to, action, value = ConnectProtocol.toJson(value))
        val r = _remote.value ?: return
        val now = SystemClock.elapsedRealtime()
        val nudged = when (action) {
            "pause" -> r.copy(playing = false, pos = positionMs(), receivedAt = now)
            "play" -> r.copy(playing = true, pos = positionMs(), receivedAt = now)
            "seek" -> r.copy(pos = (value as? Number)?.toLong() ?: r.pos, receivedAt = now)
            "volume" -> r.copy(vol = (value as? Number)?.toFloat())
            "shuffle" -> r.copy(shuffle = value == true)
            "repeat" -> r.copy(repeat = RepeatMode.fromWire(value as? String))
            // Переключение трека: таймлайн пульта сразу с нуля.
            "next", "prev", "play-context" -> r.copy(pos = 0, receivedAt = now)
            else -> null
        }
        if (nudged != null) setRemote(nudged)
    }

    /** Громкость активного устройства (0…1): ползунок и кнопки громкости телефона. */
    fun setRemoteVolume(fraction: Float) = sendCommand("volume", fraction.coerceIn(0f, 1f))

    // --- Своё воспроизведение ------------------------------------------------------

    private suspend fun watchPlayer() {
        var last: PlayerState? = null
        engine.localState.collect { s ->
            val prev = last
            last = s
            if (prev == null) return@collect
            if (s.playing != prev.playing) {
                updatePresence()
                publishDevices()
                if (s.playing) {
                    // Заиграли здесь — активное устройство теперь мы.
                    _activeId.value = myId
                    setRemote(null)
                    broadcastState()
                } else if (_activeId.value == myId) {
                    broadcastState()
                }
                return@collect
            }
            if (s.track?.id != prev.track?.id) {
                if (s.track != null && (_activeId.value == myId || _activeId.value == null)) {
                    _activeId.value = myId
                    broadcastState()
                }
                return@collect
            }
            // Перемотка и изменения очереди — пультам и в Discord сразу, а не следующим тактом.
            val changed = s.seekTick != prev.seekTick || fingerprint(s) != fingerprint(prev)
            if (changed && _activeId.value == myId && s.track != null) broadcastState()
        }
    }

    private fun fingerprint(s: PlayerState): String = buildString {
        s.queue.manual.joinTo(this, ",") { it.qid.toString() }
        append('|')
        s.queue.context.joinTo(this, ",") { it.id.toString() }
        append('|').append(s.queue.index).append('|').append(s.queue.shuffle).append('|').append(s.queue.repeat)
    }

    // --- Вид активного устройства ---------------------------------------------------

    private fun setRemote(state: RemoteState?) {
        _remote.value = state
        rebuildView()
        if (state != null) resolveTracks(state)
    }

    /** Полные треки для экранов: обложки в цвет, исполнители-ссылки, текст песни. */
    private fun resolveTracks(state: RemoteState) {
        val ids = (listOfNotNull(state.track?.id) + state.up.take(30) + state.man.map { it.second }).distinct()
        val missing = ids.filter { trackCache.get(it) == null }
        if (missing.isEmpty() || resolveJob?.isActive == true) return
        resolveJob = scope.launch {
            engine.fetchTracks(missing).forEach { trackCache.put(it.id, it) }
            rebuildView()
            // Пока грузили, могли прийти новые id.
            _remote.value?.let { latest -> if (latest !== state) resolveTracks(latest) }
        }
    }

    private fun rebuildView() {
        val r = _remote.value
        val light = r?.track
        remoteView = if (r == null || light == null) {
            null
        } else {
            val current = trackCache.get(light.id) ?: light.toTrack()
            val upcoming = r.up.mapNotNull { trackCache.get(it) }
            PlayerState(
                queue = QueueSnapshot(
                    current = NowPlaying(current),
                    context = (listOf(current) + upcoming).toImmutableList(),
                    index = 0,
                    manual = r.man.mapNotNull { (q, id) -> trackCache.get(id)?.let { QueuedTrack(q, it) } }.toImmutableList(),
                    contextName = r.ctx,
                    contextKey = r.ctxKey,
                    shuffle = r.shuffle,
                    repeat = r.repeat,
                ),
                playing = r.playing,
                durationMs = r.dur.takeIf { it > 0 } ?: light.durationMs,
            )
        }
        engine.remoteChanged()
    }

    private fun LightTrack.toTrack(): Track = Track(
        id = id,
        title = title,
        durationMs = durationMs,
        artists = persistentListOf(ArtistRef(0, artists, "")),
        release = album?.let { ReleaseRef(0, it, releaseSlug.orEmpty()) },
        cover = listOfNotNull(cover?.let { 64 to it }, coverBig?.let { 1000 to it }).takeIf { it.isNotEmpty() }?.let { Cover(it.toImmutableList()) },
        streamUrl = "",
        audioRev = null,
        loudnessLufs = null,
    )

    private fun publishDevices() {
        val now = SystemClock.elapsedRealtime()
        val alive = others.values.filter { (_, seen) -> now - seen < DEVICE_TTL_MS }.map { it.first }
        _devices.value = listOf(me.copy(playing = engine.localState.value.playing)) + alive
    }

    /** Устройство замолчало дольше 30 секунд — из списка; активное — забываем. */
    private fun pruneDevices() {
        val now = SystemClock.elapsedRealtime()
        val before = others.size
        others.entries.removeAll { now - it.value.second >= DEVICE_TTL_MS }
        if (others.size != before) publishDevices()
        val r = _remote.value
        if (r != null && now - r.receivedAt >= DEVICE_TTL_MS) {
            if (_activeId.value == r.device.id) _activeId.value = null
            setRemote(null)
        }
    }

    // --- Громкость телефона ----------------------------------------------------------

    private fun systemVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun setSystemVolume(fraction: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction.coerceIn(0f, 1f) * max).roundToInt(), 0)
    }

    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "SukifyConnect"
        const val PREFS = "connect"
        const val KEY_DEVICE_ID = "device_id"
        const val TYPE_PHONE = "phone"
        const val HELLO_INTERVAL_MS = 10_000L
        const val STATE_INTERVAL_MS = 5_000L
        const val DEVICE_TTL_MS = 30_000L
    }
}
