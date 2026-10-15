package ru.nepalimsya.sukify.playback

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.offline.OfflineCatalog
import ru.nepalimsya.sukify.data.offline.OfflineState
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.playback.audio.CurveType
import ru.nepalimsya.sukify.playback.audio.DeckAutomation
import ru.nepalimsya.sukify.playback.audio.Envelope
import ru.nepalimsya.sukify.playback.audio.EqualizerStore
import ru.nepalimsya.sukify.playback.mix.MixAutomations
import ru.nepalimsya.sukify.playback.mix.MixShapes
import ru.nepalimsya.sukify.playback.mix.Shapes
import ru.nepalimsya.sukify.playback.mix.TransitionResolver
import ru.nepalimsya.sukify.playback.queue.QueueModel
import ru.nepalimsya.sukify.playback.queue.QueueSnapshot
import ru.nepalimsya.sukify.playback.queue.QueuedTrack
import ru.nepalimsya.sukify.playback.queue.RepeatMode
import ru.nepalimsya.sukify.ui.components.Toasts
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/** Состояние плеера для интерфейса, уведомления и Connect. */
@Immutable
data class PlayerState(
    val queue: QueueSnapshot = QueueSnapshot(),
    /** Пользователь хочет, чтобы играло (иконка ⏸). */
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    /** Растёт при каждой перемотке — Connect и джем узнают о ней сразу. */
    val seekTick: Int = 0,
    val crossfading: Boolean = false,
) {
    val track: Track? get() = queue.current?.track
}

/**
 * Пульт другого устройства (Sukify Connect, фаза 6): пока активно другое
 * устройство, действия плеера — это команды ему, как в player.js.
 */
interface RemoteControl {
    val active: Boolean

    fun send(action: String, value: Any? = null)

    /** Что играет на активном устройстве — его экраны показывают вместо своего. */
    val view: PlayerState?

    /** Позиция на активном устройстве: из его последней трансляции плюс прошедшее время. */
    fun positionMs(): Long
}

/** План перехода к следующему треку: где начать затухание и как. */
data class TransitionPlan(
    /** Позиция уходящего трека, с которой начинается переход, мс. */
    val outStartMs: Long,
    /** С какого места играет приходящий трек, мс. */
    val inStartMs: Long,
    /** Длина перекрытия, мс; 0 — бесшовная смена без перекрытия. */
    val lengthMs: Long,
    val curve: CurveType = CurveType.EqualPower,
    /** Формы микса (громкость/эквалайзер/фильтр); null — простой кроссфейд по [curve]. */
    val shapes: Shapes? = null,
) {
    val outEndMs: Long get() = outStartMs + lengthMs
}

/**
 * Плеер: очередь (QueueModel), две деки и всё между ними.
 *
 * Переход к следующему треку всегда идёт через вторую деку: за ~20 с до
 * конца следующий трек готовится на ней (буферизуется, получает свою
 * громкость нормализации), а в нужный момент запускается — либо впритык
 * (бесшовно), либо внахлёст с кроссфейдом. Громкость по огибающей считает
 * DeckProcessor по медиавремени, поэтому стык звучит одинаково точно и с
 * выключенным экраном.
 *
 * Всё, кроме загрузки треков, работает на главном потоке.
 */
@Singleton
class PlaybackEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: MediaSources,
    private val settings: PlaybackSettings,
    private val logger: PlayLogger,
    private val sessions: PlayerSessionStore,
    private val library: LibraryState,
    private val api: SukifyApi,
    private val offline: OfflineState,
    private val offlineCatalog: OfflineCatalog,
    private val transitions: TransitionResolver,
    /** Эквалайзер читают процессоры дек; здесь — чтобы настройки загрузились до первого звука. */
    @Suppress("unused") private val equalizer: EqualizerStore,
) : AudioFocus.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = QueueModel()

    private val _local = MutableStateFlow(PlayerState())

    /** Своё воспроизведение — для уведомления, вещания Connect и джема. */
    val localState: StateFlow<PlayerState> = _local.asStateFlow()

    private val _state = MutableStateFlow(PlayerState())

    /** Что показывать на экранах: своё или, пока активно другое устройство, его (Connect). */
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Пульт другого устройства — проставляет Connect. */
    var remote: RemoteControl? = null

    /**
     * Таймер сна «до конца трека»: следующий не готовим, на конце трека —
     * пауза. [onStoppedAfterCurrent] — таймеру, что сработало.
     */
    var stopAfterCurrent = false
    var onStoppedAfterCurrent: () -> Unit = {}

    /** Как переходить между парой треков: переход сообщества или кроссфейд из настроек. */
    var transitionPlanner: (from: Track, to: Track, fromDurationMs: Long, toDurationMs: Long) -> TransitionPlan =
        { from, to, fromDuration, toDuration ->
            transitions.plan(from, to, fromDuration, toDuration, settings.current.crossfadeSeconds * 1000L)
        }

    private var decks: Array<Deck>? = null
    private var active = 0
    private var wantPlay = false
    private var pausedByFocus = false
    private var restoredPositionMs = 0L
    private var seekTick = 0
    private var tickJob: Job? = null
    private var fadeJob: Job? = null
    private var persistJob: Job? = null

    /** Ждём аудиофокус, пока Media3 поднимает foreground-сервис (запуск из фона). */
    private var focusWait: Job? = null
    private val focus by lazy { AudioFocus(context, this) }

    /** Следующий трек, уже загруженный во вторую деку. */
    private var prepared: Prepared? = null

    /** Уходящая дека во время перехода. */
    private var outgoing: Deck? = null

    /**
     * Треки, чей файл не открылся (404, битый): их не готовим заново на каждом
     * такте и перескакиваем. Ключ — id+ревизия: перезалили файл — попробуем снова.
     */
    private val broken = HashSet<String>()

    /** Подготовка следующего упала из-за сети — не долбим сервер каждый такт. */
    private var prepareRetryAt = 0L

    private data class Prepared(val track: Track, val deck: Deck, val plan: TransitionPlan, val inAutomation: DeckAutomation)

    /** Где кончается перекрытие на уходящей деке, мс (для идущего перехода). */
    private var outgoingEndMs = Long.MAX_VALUE

    init {
        // Лайки меняются и из фона (реалтайм, синхронизация), а плеер Media3 живёт на главном потоке.
        library.onLikesChanged = { scope.launch { notifyChanged() } }
    }

    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    fun removeChangeListener(listener: () -> Unit) {
        changeListeners -= listener
    }

    // --- Команды ---------------------------------------------------------------

    /** Включить подборку с трека [start] (null — с первого). */
    fun playContext(tracks: List<Track>, start: Track?, name: String, key: String) {
        remoteOrNull()?.let {
            val playable = tracks.filter { t -> t.playable }
            val index = playable.indexOfFirst { t -> t.id == start?.id }.coerceAtLeast(0)
            it.send("play-context", PlayContextCommand(playable.map { t -> t.id }, index, name, key))
            return
        }
        queue.smartShuffle = settings.current.smartShuffle
        queue.isLiked = library::isLiked
        // Без сети подборка — только из скачанного (как офлайн-режим Spotify).
        val list = if (offline.onlyDownloaded.value) tracks.filter(offline::isAvailable) else tracks
        if (list.isEmpty()) {
            Toasts.show("Без сети здесь нечего слушать — ничего не скачано")
            return
        }
        val first = start?.takeIf { s -> list.any { it.id == s.id } } ?: list.firstOrNull { it.playable }
        val track = queue.playContext(list, first, name, key) ?: return
        loadAndPlay(track, 0)
    }

    fun togglePlay() {
        val r = remoteOrNull()
        if (r != null) {
            r.send(if (_state.value.playing) "pause" else "play")
            return
        }
        if (wantPlay) pause() else play()
    }

    fun play() {
        remoteOrNull()?.let {
            it.send("play")
            return
        }
        val track = queue.current?.track ?: return
        val deck = decks()[active]
        // Восстановленная сессия: звук ещё не загружен — грузим с сохранённой позиции.
        if (deck.track?.id != track.id) {
            loadAndPlay(track, restoredPositionMs)
            return
        }
        if (deck.player.playbackState == Player.STATE_ENDED) deck.seekTo(0)
        startPlayback(deck)
        publish()
    }

    fun pause() {
        remoteOrNull()?.let {
            it.send("pause")
            return
        }
        pauseLocal()
    }

    /** Пауза строго своего звука — без пульта (нужна Connect: «заиграло другое устройство»). */
    fun pauseLocal() {
        wantPlay = false
        pausedByFocus = false
        focusWait?.cancel()
        focusWait = null
        // Пауза посреди перехода: уходящий трек глушим сразу, иначе он
        // доиграл бы своё затухание после паузы.
        finishTransitionNow()
        decks?.forEach { it.player.playWhenReady = false }
        focus.abandon()
        stopTicker()
        persistNow()
        publish()
    }

    fun next() {
        remoteOrNull()?.let {
            it.send("next")
            return
        }
        val target = nextPlayable(manualSkip = true)
        if (target == null) {
            stopAtEnd()
            return
        }
        loadAndPlay(target, 0)
    }

    /** Следующий трек очереди, минуя те, чей файл не открылся. */
    private fun nextPlayable(manualSkip: Boolean): Track? {
        var guard = queue.snapshot().let { it.context.size + it.manual.size } + 1
        var target = queue.next(manualSkip)
        while (target != null && !canPlay(target) && guard-- > 0) {
            target = queue.next(manualSkip = true)
        }
        return target?.takeIf(::canPlay)
    }

    /** Файл открывается и (без сети) скачан. */
    private fun canPlay(track: Track): Boolean = track.cacheKey !in broken && offline.isAvailable(track)

    /** ⏮: после 3 секунд — в начало трека; force — всегда предыдущий (свайп). */
    fun previous(force: Boolean = false) {
        remoteOrNull()?.let {
            it.send("prev")
            return
        }
        if (!force && localPositionMs() > 3_000) {
            seekTo(0)
            return
        }
        val target = queue.previous()
        if (target == null) {
            seekTo(0)
            return
        }
        loadAndPlay(target, 0)
    }

    fun seekTo(ms: Long) {
        seekTick++
        remoteOrNull()?.let {
            it.send("seek", ms)
            publish()
            return
        }
        val deck = decks?.get(active)
        if (deck == null || !deck.isLoaded) {
            restoredPositionMs = ms.coerceAtLeast(0)
            publish()
            return
        }
        finishTransitionNow()
        deck.seekTo(ms.coerceIn(0, deck.durationMs.coerceAtLeast(0)))
        // Следующий трек остаётся готовым, но затухание на уходящем пересчитаем.
        resetPrepared()
        publish()
        persistSoon()
    }

    fun setShuffle(on: Boolean) {
        remoteOrNull()?.let {
            it.send("shuffle", on)
            return
        }
        queue.smartShuffle = settings.current.smartShuffle
        queue.isLiked = library::isLiked
        queue.setShuffle(on)
        onQueueChanged()
    }

    fun cycleRepeat() = setRepeat(queue.repeat.next())

    fun setRepeat(mode: RepeatMode) {
        remoteOrNull()?.let {
            it.send("repeat", mode.wire)
            return
        }
        queue.repeat = mode
        decks?.get(active)?.player?.repeatMode =
            if (mode == RepeatMode.One) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        onQueueChanged()
    }

    // --- Очередь ---------------------------------------------------------------

    fun addToQueue(track: Track): Boolean {
        remoteOrNull()?.let {
            it.send("queue-add", listOf(track.id))
            return true
        }
        val added = queue.addToQueue(track)
        if (added) onQueueChanged()
        return added
    }

    fun removeFromManual(qid: Long) {
        remoteOrNull()?.let {
            it.send("queue-remove-manual", qid)
            return
        }
        queue.removeFromManual(qid)
        onQueueChanged()
    }

    fun removeUpcoming(i: Int) {
        remoteOrNull()?.let {
            it.send("queue-remove-upcoming", i)
            return
        }
        queue.removeUpcoming(i)
        onQueueChanged()
    }

    fun clearManual() {
        queue.clearManual()
        onQueueChanged()
    }

    fun setManual(items: List<QueuedTrack>) {
        queue.setManual(items)
        onQueueChanged()
    }

    fun setUpcoming(tracks: List<Track>) {
        queue.setUpcoming(tracks)
        onQueueChanged()
    }

    fun playManual(qid: Long) {
        remoteOrNull()?.let {
            it.send("play-manual", qid)
            return
        }
        queue.playManual(qid)?.let { loadAndPlay(it, 0) }
    }

    fun playUpcoming(i: Int) {
        remoteOrNull()?.let {
            it.send("play-upcoming", i)
            return
        }
        queue.playUpcoming(i)?.let { loadAndPlay(it, 0) }
    }

    /** Лайк играющего трека — из уведомления и экрана «Сейчас играет». */
    fun toggleLikeCurrent() {
        val track = queue.current?.track ?: return
        scope.launch { library.toggleLike(track) }
    }

    fun isLiked(trackId: Long): Boolean = library.isLiked(trackId)

    // --- Позиция ---------------------------------------------------------------

    /** Позиция того, что видно на экране: своя или активного устройства (Connect). */
    fun positionMs(): Long = remoteOrNull()?.positionMs() ?: localPositionMs()

    /** Где играем сейчас, мс — честное время из аудио, не из анимации. */
    fun localPositionMs(): Long {
        val deck = decks?.get(active)
        return if (deck != null && deck.isLoaded) deck.positionMs else restoredPositionMs
    }

    fun localDurationMs(): Long {
        val deck = decks?.get(active)
        return if (deck != null && deck.isLoaded) deck.durationMs else (queue.current?.track?.durationMs ?: 0)
    }

    /**
     * Заиграло другое устройство (Connect): гасим себя за [ms] и встаём на
     * паузу — передача звучит как переход, а не обрыв.
     */
    fun fadeOutAndPause(ms: Long = 1_100, then: () -> Unit = {}) {
        if (!wantPlay) return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val steps = (ms / 40).toInt().coerceIn(12, 240)
            for (i in 1..steps) {
                val v = 1f - i.toFloat() / steps
                decks?.forEach { it.player.volume = v * v }
                delay(ms / steps)
            }
            pauseLocal()
            decks?.forEach { it.player.volume = 1f }
            fadeJob = null
            then()
        }
    }

    /** Активное устройство сменилось или прислало новое состояние — перерисовать экраны. */
    fun remoteChanged() {
        merge()
        notifyChanged()
    }

    private fun merge() {
        _state.value = remoteOrNull()?.view ?: _local.value
    }

    fun snapshot(): QueueSnapshot = queue.snapshot()

    // --- Сессия ------------------------------------------------------------------

    /** Поднять прошлую сессию на паузе — как restoreSession() в вебе. */
    suspend fun restoreSession() {
        if (queue.current != null) return
        val saved = sessions.load() ?: return
        val ids = (saved.queueIds + saved.manualIds + saved.sourceIds).distinct()
        val byId = fetchTracks(ids).associateBy { it.id }
        val context = saved.queueIds.mapNotNull(byId::get)
        // Пока грузили, пользователь мог уже что-то включить.
        if (context.isEmpty() || queue.current != null) return
        queue.restore(
            tracks = context,
            index = saved.index,
            manual = saved.manualIds.mapNotNull(byId::get),
            name = saved.name,
            key = saved.key,
            shuffle = saved.shuffle,
            source = saved.sourceIds.mapNotNull(byId::get),
            repeat = RepeatMode.fromWire(saved.repeat),
        )
        restoredPositionMs = saved.positionMs.coerceIn(0, queue.current?.track?.durationMs ?: 0)
        publish()
    }

    /**
     * Что играло в прошлый раз и с какого места — системному «Продолжить»:
     * ему нужны только название и обложка, звук не грузим.
     */
    suspend fun lastSessionTrack(): Pair<Track, Long>? {
        queue.current?.let { return it.track to localPositionMs() }
        val saved = sessions.load() ?: return null
        val id = saved.queueIds.getOrNull(saved.index) ?: return null
        val track = fetchTracks(listOf(id)).firstOrNull() ?: return null
        return track to saved.positionMs
    }

    /** Перенос воспроизведения сюда (Connect) или состояние джема. */
    fun hydrate(tracks: List<Track>, index: Int, manual: List<Track>, positionMs: Long, play: Boolean, name: String, key: String) {
        val track = queue.restore(tracks, index, manual, name, key) ?: return
        loadAndPlay(track, positionMs, autoplay = play)
    }

    /** Подменить очередь соседа по джему, не трогая звук. */
    fun applyQueueSnapshot(tracks: List<Track>?, index: Int?, manual: List<Track>?, shuffle: Boolean?, source: List<Track>?) {
        queue.applySnapshot(tracks, index, manual, shuffle, source)
        onQueueChanged()
    }

    fun shuffleSource(): List<Track> = queue.shuffleSource()

    /** Выход из аккаунта: тишина и пустая очередь, прошлая сессия забыта. */
    suspend fun reset() = withContext(Dispatchers.Main.immediate) {
        pauseLocal()
        decks?.forEach { it.clear() }
        queue.clear()
        prepared = null
        outgoing = null
        broken.clear()
        prepareRetryAt = 0
        restoredPositionMs = 0
        sessions.clear()
        publish()
    }

    // --- Внутренности --------------------------------------------------------------

    private var forceLocal = 0

    private fun remoteOrNull(): RemoteControl? = if (forceLocal > 0) null else remote?.takeIf { it.active }

    /**
     * Команды внутри [block] — своему звуку, даже если активно другое
     * устройство: из уведомления (оно про свой плеер) и команды Connect,
     * пришедшие сюда.
     */
    fun <T> local(block: PlaybackEngine.() -> T): T {
        forceLocal++
        try {
            return block()
        } finally {
            forceLocal--
        }
    }

    private fun decks(): Array<Deck> = decks ?: Array(2) { Deck(context, sources, it) }.also { created ->
        decks = created
        created.forEach { deck -> deck.player.addListener(DeckListener(deck)) }
    }

    private fun gainFor(track: Track): Float = settings.current.gainFor(track.loudnessLufs)

    /** Запустить трек на активной деке с позиции [startMs], оборвав переходы. */
    private fun loadAndPlay(track: Track, startMs: Long, autoplay: Boolean = true) {
        val all = decks()
        finishTransitionNow()
        dropPrepared()
        val deck = all[active]
        debug("load «${track.title}» on deck ${deck.index} from $startMs ms (gain ${gainFor(track)}, local=${sources.isLocal(track)})")
        deck.load(track, startMs, gainFor(track))
        deck.player.repeatMode = if (queue.repeat == RepeatMode.One) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        restoredPositionMs = 0
        if (autoplay) {
            startPlayback(deck)
            logger.log(track.id)
        } else {
            wantPlay = false
        }
        publish()
        persistSoon()
    }

    private fun startPlayback(deck: Deck) {
        if (!focus.request()) {
            if (inBackground()) {
                waitForFocus(deck)
                return
            }
            Toasts.show("Звук сейчас занят другим приложением")
            wantPlay = false
            return
        }
        focusWait?.cancel()
        focusWait = null
        // Снова играем посреди затухания передачи (Connect) — громкость обратно.
        fadeJob?.let {
            it.cancel()
            fadeJob = null
            decks?.forEach { d -> d.player.volume = 1f }
        }
        wantPlay = true
        deck.player.playWhenReady = true
        startTicker()
    }

    /**
     * Android 16 не даёт аудиофокус фоновому приложению без foreground-сервиса,
     * а команды из машины, с гарнитуры и виджета приходят как раз в фоне.
     * Объявляем «играем» (снаружи это буферизация) — Media3 по этому поднимает
     * сервис с уведомлением — и просим фокус снова.
     */
    private fun waitForFocus(deck: Deck) {
        wantPlay = true
        focusWait?.cancel()
        focusWait = scope.launch {
            repeat(FOCUS_RETRIES) {
                delay(FOCUS_RETRY_MS)
                if (!wantPlay) return@launch
                if (focus.request()) {
                    focusWait = null
                    debug("audio focus granted after foreground start")
                    startPlayback(deck)
                    publish()
                    return@launch
                }
            }
            focusWait = null
            wantPlay = false
            Toasts.show("Звук сейчас занят другим приложением")
            publish()
        }
    }

    private fun inBackground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }

    private fun stopAtEnd() {
        val deck = decks?.get(active)
        wantPlay = false
        deck?.player?.playWhenReady = false
        deck?.seekTo(0)
        focus.abandon()
        stopTicker()
        publish()
        persistNow()
    }

    /**
     * В редакторе сохранили, выбрали или удалили переход пары. Кэш забываем, а
     * если эта пара уже подготовлена и переход ещё не начался — готовим заново,
     * чтобы новый переход сыграл уже сейчас.
     */
    fun transitionsChanged(fromId: Long, toId: Long) {
        transitions.invalidate(fromId, toId)
        val p = prepared ?: return
        if (outgoing == null && decks?.get(active)?.track?.id == fromId && p.track.id == toId) {
            dropPrepared()
            publish()
        }
    }

    private fun onQueueChanged() {
        // Готовый следующий трек больше не тот — освобождаем деку, такт
        // подготовит правильный.
        val next = queue.peekNext()
        if (prepared != null && prepared?.track?.id != next?.id) dropPrepared()
        publish()
        persistSoon()
    }

    // --- Переходы ------------------------------------------------------------------

    /** Такт плеера: готовим следующий трек и начинаем переход вовремя. */
    private fun tick(): Long {
        val all = decks ?: return IDLE_TICK_MS
        val deck = all[active]
        // Перекрытие кончилось раньше файла (переход из редактора посреди трека) — гасим уходящую.
        outgoing?.let { out -> if (out.positionMs >= outgoingEndMs) finishTransitionNow() }
        if (!wantPlay || !deck.isLoaded || queue.repeat == RepeatMode.One) return IDLE_TICK_MS
        val duration = deck.durationMs
        if (duration <= 0) return IDLE_TICK_MS
        val position = deck.positionMs

        if (stopAfterCurrent) {
            // Таймер сна: последние секунды трека тихо гаснут, дальше — пауза.
            if (duration - position <= SLEEP_FADE_MS && fadeJob == null) {
                fadeOutAndPause((duration - position).coerceAtLeast(300)) {
                    // Трек мог успеть доиграть раньше конца затухания — тогда уже сообщили.
                    if (stopAfterCurrent) {
                        stopAfterCurrent = false
                        onStoppedAfterCurrent()
                    }
                }
            }
            return NORMAL_TICK_MS
        }
        if (prepared == null && outgoing == null && SystemClock.elapsedRealtime() >= prepareRetryAt) {
            val current = deck.track
            val next = queue.peekNext()
            // Переход пары ещё грузится — подождём, пока до конца далеко.
            val waiting = current != null && next != null && transitions.isPending(current, next) &&
                duration - position > settings.current.crossfadeSeconds * 1000L + PENDING_GRACE_MS
            if (current != null && next != null && canPlay(next) && !waiting) {
                val plan = transitionPlanner(current, next, duration, next.durationMs.takeIf { it > 0 } ?: duration)
                // Готовим заранее: переход из редактора может начинаться задолго до конца.
                if (position >= plan.outStartMs - PREPARE_AHEAD_MS) prepareNext(deck, next, plan)
            }
        }
        val plan = prepared?.plan
        if (plan != null && outgoing == null) {
            // Бесшовно: запускаем приходящий чуть раньше конца — на время,
            // за которое звук доходит до динамика.
            val startAt = if (plan.lengthMs > 0) plan.outStartMs else duration - SEAMLESS_LEAD_MS
            if (position >= startAt) {
                beginTransition()
                return FAST_TICK_MS
            }
            val untilStart = startAt - position
            if (untilStart < 3_000) return untilStart.coerceIn(5, FAST_TICK_MS)
        }
        return if (outgoing != null) FAST_TICK_MS * 5 else NORMAL_TICK_MS
    }

    private fun prepareNext(current: Deck, next: Track, plan: TransitionPlan) {
        val idle = decks()[current.index xor 1]
        val (outAuto, inAuto) = automations(plan)
        idle.load(next, plan.inStartMs, gainFor(next), inAuto)
        // Затухание уходящего — заранее: процессор обрабатывает звук с опережением.
        if (plan.lengthMs > 0) current.processor.automation = outAuto
        prepared = Prepared(next, idle, plan, inAuto)
        debug("prepared «${next.title}» on deck ${idle.index}: $plan")
    }

    private fun automations(plan: TransitionPlan) = MixAutomations.forPlan(plan)

    private fun beginTransition() {
        val next = prepared ?: return
        val from = decks()[active]
        val target = queue.next(manualSkip = false)
        if (target?.id != next.track.id) {
            // Очередь поменялась в последний момент — играем то, что в ней.
            dropPrepared()
            if (target != null) loadAndPlay(target, 0) else stopAtEnd()
            return
        }
        val plan = next.plan
        // Опоздали со стартом (главный поток был занят) — сдвигаем огибающие
        // приходящего, чтобы кривые обеих дек шли вместе, как задумано.
        val lateMs = if (plan.lengthMs > 0) from.positionMs - plan.outStartMs else 0
        if (lateMs > LATE_COMPENSATE_MS) next.deck.processor.automation = next.inAutomation.shiftedBy(-lateMs * 1000)
        prepared = null
        outgoing = from
        outgoingEndMs = if (plan.lengthMs > 0) plan.outEndMs else Long.MAX_VALUE
        active = next.deck.index
        next.deck.player.playWhenReady = true
        debug("transition → «${target.title}» deck ${from.index}→${next.deck.index} at ${from.positionMs}/${from.durationMs} ms, overlap ${plan.lengthMs} ms, late ${lateMs} ms, shapes ${plan.shapes}")
        logger.log(target.id)
        publish()
        persistSoon()
    }

    /** Переход закончился или его оборвали: уходящую деку — в тишину. */
    private fun finishTransitionNow() {
        val out = outgoing ?: return
        outgoing = null
        outgoingEndMs = Long.MAX_VALUE
        out.clear()
        decks?.get(active)?.processor?.automation = DeckAutomation.Neutral
        publish()
    }

    private fun dropPrepared() {
        val p = prepared ?: return
        prepared = null
        p.deck.clear()
        decks?.get(active)?.processor?.automation = DeckAutomation.Neutral
    }

    /** После перемотки затухание у уходящего пересчитаем заново. */
    private fun resetPrepared() = dropPrepared()

    private fun onDeckEnded(deck: Deck) {
        debug("deck ${deck.index} ended («${deck.track?.title}»), active=$active, outgoing=${outgoing?.index}")
        if (deck === outgoing) {
            finishTransitionNow()
            publish()
            return
        }
        if (deck.index != active) return
        if (stopAfterCurrent) {
            stopAfterCurrent = false
            fadeJob?.cancel()
            fadeJob = null
            decks?.forEach { it.player.volume = 1f }
            stopAtEnd()
            onStoppedAfterCurrent()
            return
        }
        // Активная дека доиграла, а переход не случился (не успели
        // подготовить, нет сети) — обычный «следующий».
        val target = nextPlayable(manualSkip = false)
        if (target != null) loadAndPlay(target, 0) else stopAtEnd()
    }

    private fun onDeckError(deck: Deck, error: PlaybackException) {
        Log.w(TAG, "deck ${deck.index} error on «${deck.track?.title}»: ${error.errorCodeName}", error)
        val network = error.errorCode in NETWORK_ERRORS
        if (deck.index != active) {
            val track = deck.track
            if (prepared?.deck === deck) dropPrepared() else deck.clear()
            if (network) {
                prepareRetryAt = SystemClock.elapsedRealtime() + PREPARE_RETRY_MS
            } else if (track != null) {
                broken += track.cacheKey
            }
            return
        }
        val title = deck.track?.title.orEmpty()
        if (network) {
            Toasts.show("Нет сети — «$title» не скачан")
            pauseLocal()
            return
        }
        deck.track?.let { broken += it.cacheKey }
        Toasts.show("Не удалось воспроизвести «$title»")
        scope.launch {
            delay(600)
            next()
        }
    }

    private inner class DeckListener(private val deck: Deck) : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onDeckEnded(deck)
            if (deck.index == active) publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (deck.index == active) publish()
        }

        override fun onPlayerError(error: PlaybackException) = onDeckError(deck, error)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Повтор одного трека: каждый круг — новое прослушивание.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && deck.index == active) {
                deck.track?.let { logger.log(it.id) }
            }
        }
    }

    // --- Аудиофокус ------------------------------------------------------------------

    override fun onFocusLost() = pauseLocal()

    override fun onFocusLostTransient() {
        if (!wantPlay) return
        pauseLocal()
        pausedByFocus = true
    }

    override fun onDuck(ducked: Boolean) {
        decks?.forEach { it.player.volume = if (ducked) 0.2f else 1f }
    }

    override fun onFocusRegained() {
        if (pausedByFocus) {
            pausedByFocus = false
            play()
        }
    }

    override fun onBecomingNoisy() = pauseLocal()

    // --- Служебное -------------------------------------------------------------------

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            var lastPersist = SystemClock.elapsedRealtime()
            while (isActive) {
                val wait = tick()
                val now = SystemClock.elapsedRealtime()
                if (now - lastPersist > PERSIST_EVERY_MS) {
                    lastPersist = now
                    persistNow()
                }
                delay(wait)
            }
        }
    }

    private fun stopTicker() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun publish() {
        val deck = decks?.get(active)
        val snapshot = queue.snapshot()
        // Переход к следующему узнаём заранее — к моменту подготовки он уже известен.
        snapshot.current?.track?.let { cur -> queue.peekNext()?.let { next -> transitions.prefetch(cur, next) } }
        _local.value = PlayerState(
            queue = snapshot,
            playing = wantPlay,
            buffering = wantPlay && (focusWait != null || deck?.player?.playbackState == Player.STATE_BUFFERING),
            durationMs = localDurationMs(),
            seekTick = seekTick,
            crossfading = outgoing != null,
        )
        merge()
        notifyChanged()
    }

    private fun notifyChanged() = changeListeners.forEach { it() }

    private fun persistSoon() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(800)
            persistNow()
        }
    }

    private fun persistNow() {
        val snapshot = queue.snapshot()
        if (snapshot.current == null) return
        val session = SavedSession(
            queueIds = snapshot.context.map { it.id },
            manualIds = snapshot.manual.map { it.track.id },
            index = snapshot.index,
            positionMs = localPositionMs(),
            name = snapshot.contextName,
            key = snapshot.contextKey,
            shuffle = snapshot.shuffle,
            repeat = snapshot.repeat.wire,
            sourceIds = queue.shuffleSource().map { it.id },
        )
        scope.launch { sessions.save(session) }
    }

    /** Треки по id пачками по 500 (лимит /tracks-bulk) — порядок как просили. */
    suspend fun fetchTracks(ids: List<Long>): List<Track> {
        val result = mutableListOf<Track>()
        for (chunk in ids.chunked(500)) {
            val page = runCatching { api.tracksBulk(chunk.joinToString(",")).data }.getOrNull() ?: continue
            result += page.map { it.toDomain() }
        }
        // Без сети — из офлайн-копии медиатеки: скачанное восстановится и так.
        val missing = ids - result.mapTo(HashSet()) { it.id }
        if (missing.isNotEmpty()) result += offlineCatalog.tracks(missing).map { it.toDomain() }
        val byId = result.associateBy { it.id }
        return ids.mapNotNull(byId::get)
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "SukifyEngine"

        /** Таймер сна: столько длится затухание перед паузой. */
        const val SLEEP_FADE_MS = 10_000L

        /** За сколько до конца готовить следующий трек. */
        const val PREPARE_AHEAD_MS = 20_000L

        /** Задержка между play() и звуком в динамике — на столько раньше стартуем стык. */
        const val SEAMLESS_LEAD_MS = 40L

        private const val FOCUS_RETRIES = 12
        private const val FOCUS_RETRY_MS = 250L

        private const val NORMAL_TICK_MS = 250L
        private const val FAST_TICK_MS = 20L
        private const val IDLE_TICK_MS = 500L
        private const val PREPARE_RETRY_MS = 5_000L

        /** С какого опоздания старта перехода сдвигать огибающие приходящего. */
        private const val LATE_COMPENSATE_MS = 15L

        /** Сколько до конца трека ещё можно ждать ответа про переход пары. */
        private const val PENDING_GRACE_MS = 5_000L
        private const val PERSIST_EVERY_MS = 15_000L

        private val NETWORK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )
    }
}

/** Команда «включи подборку» для Connect — как play-context в devices.js. */
data class PlayContextCommand(val ids: List<Long>, val index: Int, val name: String, val key: String)
