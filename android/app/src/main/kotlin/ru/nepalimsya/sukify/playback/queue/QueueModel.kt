package ru.nepalimsya.sukify.playback.queue

import androidx.compose.runtime.Immutable
import kotlin.random.Random
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import ru.nepalimsya.sukify.domain.Track

enum class RepeatMode(val wire: String) {
    Off("off"),
    All("all"),
    One("one"),
    ;

    fun next(): RepeatMode = when (this) {
        Off -> All
        All -> One
        One -> Off
    }

    companion object {
        fun fromWire(value: String?): RepeatMode = entries.firstOrNull { it.wire == value } ?: Off
    }
}

/** Трек из ручной очереди «Далее в очереди»; qid различает повторы одного трека. */
@Immutable
data class QueuedTrack(val qid: Long, val track: Track)

/** Что сейчас играет и откуда оно взялось. */
@Immutable
data class NowPlaying(val track: Track, val fromManualQid: Long? = null)

/** Снимок очереди для интерфейса и Connect — неизменяемый. */
@Immutable
data class QueueSnapshot(
    val current: NowPlaying? = null,
    val context: ImmutableList<Track> = persistentListOf(),
    val index: Int = -1,
    val manual: ImmutableList<QueuedTrack> = persistentListOf(),
    val contextName: String = "",
    val contextKey: String = "",
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
) {
    /** Треки контекста после курсора — «Далее из: …». */
    val upcoming: List<Track> get() = if (index + 1 < context.size) context.subList(index + 1, context.size) else emptyList()

    val previous: Track? get() = context.getOrNull(index - 1)
}

/**
 * Очередь воспроизведения — порт логики frontend/src/stores/player.js:
 *
 *  - контекст (альбом, плейлист, «Любимые») и курсор в нём;
 *  - ручная очередь «Далее в очереди» играет раньше продолжения контекста;
 *  - перемешивание тасует только то, что после курсора, и помнит исходный
 *    порядок, чтобы вернуть его при выключении;
 *  - повтор: off / all (по кругу) / one (один трек).
 *
 * Никакого звука здесь нет — только решения «что дальше», поэтому всё это
 * проверяется обычными юнит-тестами.
 */
class QueueModel(private val random: Random = Random.Default) {

    var context: List<Track> = emptyList()
        private set
    var index: Int = -1
        private set
    var manual: List<QueuedTrack> = emptyList()
        private set
    var current: NowPlaying? = null
        private set
    var contextName: String = ""
        private set
    var contextKey: String = ""
        private set
    var shuffle: Boolean = false
        private set
    var repeat: RepeatMode = RepeatMode.Off

    /** Включено «умное» перемешивание (настройка пользователя). */
    var smartShuffle: Boolean = false

    /** Лайкнут ли трек — умному перемешиванию нужно для весов. */
    var isLiked: (Long) -> Boolean = { false }

    private var originalOrder: List<Track>? = null
    private var qidCounter = 0L

    fun snapshot() = QueueSnapshot(
        current = current,
        context = context.toImmutableList(),
        index = index,
        manual = manual.toImmutableList(),
        contextName = contextName,
        contextKey = contextKey,
        shuffle = shuffle,
        repeat = repeat,
    )

    /**
     * Включить подборку с трека [start]. Треки без звука в очередь не попадают
     * (как в вебе), а стартовый ищем уже среди играбельных — по id, а не по
     * номеру в исходном списке.
     */
    fun playContext(tracks: List<Track>, start: Track?, name: String, key: String): Track? {
        val playable = tracks.filter { it.playable }
        context = playable
        index = start?.let { s -> playable.indexOfFirst { it.id == s.id } }?.takeIf { it >= 0 } ?: 0
        if (playable.isEmpty()) index = -1
        contextName = name
        contextKey = key
        originalOrder = null
        if (shuffle) reshuffleUpcoming()
        current = context.getOrNull(index)?.let { NowPlaying(it) }
        return current?.track
    }

    /** Следующий трек с учётом ручной очереди и повтора — им живут карусели обложек. */
    fun peekNext(): Track? = peekNextEntry()?.track

    private fun peekNextEntry(): NowPlaying? = when {
        manual.isNotEmpty() -> NowPlaying(manual.first().track, manual.first().qid)
        index + 1 < context.size -> NowPlaying(context[index + 1])
        repeat == RepeatMode.All && context.isNotEmpty() -> NowPlaying(context[0])
        else -> null
    }

    /**
     * Перейти к следующему. [manualSkip] — нажали «Далее»: при повторе одного
     * трека идём дальше (держать человека на одном треке после явного
     * «следующий» — странно). Сам доигравший трек при повторе одного
     * начинается заново.
     */
    fun next(manualSkip: Boolean): Track? {
        if (repeat == RepeatMode.One && !manualSkip) return current?.track
        val target = peekNextEntry() ?: return null
        consume(target)
        current = target
        return target.track
    }

    private fun consume(target: NowPlaying) {
        if (target.fromManualQid != null && manual.firstOrNull()?.qid == target.fromManualQid) {
            manual = manual.drop(1)
        } else if (index + 1 < context.size) {
            index++
        } else if (repeat == RepeatMode.All) {
            index = 0
        }
    }

    /** Предыдущий трек контекста; null — дальше назад некуда (тогда перематывают в начало). */
    fun previous(): Track? {
        if (index <= 0) return null
        index--
        current = NowPlaying(context[index])
        return current?.track
    }

    fun hasPrevious(): Boolean = index > 0

    fun addToQueue(track: Track): Boolean {
        if (!track.playable) return false
        manual = manual + QueuedTrack(++qidCounter, track)
        return true
    }

    fun removeFromManual(qid: Long) {
        manual = manual.filterNot { it.qid == qid }
    }

    fun clearManual() {
        manual = emptyList()
    }

    /** Новый порядок ручной очереди (перетаскивание в шите очереди). */
    fun setManual(items: List<QueuedTrack>) {
        manual = items
    }

    /** Убрать i-й трек из «Далее из: …» (0 — сразу после текущего). */
    fun removeUpcoming(i: Int) {
        val abs = index + 1 + i
        if (abs in context.indices) context = context.toMutableList().apply { removeAt(abs) }
    }

    /** Новый порядок того, что после курсора. */
    fun setUpcoming(tracks: List<Track>) {
        context = context.take(index + 1) + tracks
    }

    fun playManual(qid: Long): Track? {
        val item = manual.firstOrNull { it.qid == qid } ?: return null
        manual = manual.filterNot { it.qid == qid }
        current = NowPlaying(item.track, item.qid)
        return item.track
    }

    fun playUpcoming(i: Int): Track? {
        val abs = index + 1 + i
        val track = context.getOrNull(abs) ?: return null
        index = abs
        current = NowPlaying(track)
        return track
    }

    /** Прыжок по номеру в контексте (Android Auto, системная очередь). */
    fun jumpTo(contextIndex: Int): Track? {
        val track = context.getOrNull(contextIndex) ?: return null
        index = contextIndex
        current = NowPlaying(track)
        return track
    }

    fun setShuffle(on: Boolean) {
        shuffle = on
        if (context.isEmpty()) return
        if (on) {
            reshuffleUpcoming()
        } else {
            originalOrder?.let { original ->
                val playingId = current?.track?.id
                context = original
                originalOrder = null
                val i = context.indexOfFirst { it.id == playingId }
                if (i >= 0) index = i
            }
        }
    }

    /** Исходный порядок до перемешивания — джем отдаёт его соседям. */
    fun shuffleSource(): List<Track> = originalOrder.orEmpty()

    private fun reshuffleUpcoming() {
        if (originalOrder == null) originalOrder = context
        val head = context.take(index + 1)
        val rest = context.drop(index + 1)
        context = head + if (smartShuffle) smartShuffled(rest) else rest.shuffled(random)
    }

    /**
     * «Умное» перемешивание (lib/smartShuffle.js): не ставить подряд одного
     * исполнителя и чуть чаще поднимать лайкнутое.
     */
    private fun smartShuffled(tracks: List<Track>): List<Track> {
        val pool = tracks.toMutableList()
        val result = ArrayList<Track>(pool.size)
        var lastArtist: Long? = current?.track?.artists?.firstOrNull()?.id
        while (pool.isNotEmpty()) {
            val weights = pool.map { t ->
                var w = 1.0
                if (isLiked(t.id)) w += 0.6
                if (t.artists.firstOrNull()?.id == lastArtist && lastArtist != null) w *= 0.15
                w
            }
            var r = random.nextDouble() * weights.sum()
            var pick = pool.lastIndex
            for (i in weights.indices) {
                r -= weights[i]
                if (r <= 0) {
                    pick = i
                    break
                }
            }
            val chosen = pool.removeAt(pick)
            result += chosen
            lastArtist = chosen.artists.firstOrNull()?.id
        }
        return result
    }

    /**
     * Поднять очередь целиком: перенос с другого устройства (Connect),
     * восстановление сессии, состояние джема.
     */
    fun restore(
        tracks: List<Track>,
        index: Int,
        manual: List<Track>,
        name: String,
        key: String,
        shuffle: Boolean = this.shuffle,
        source: List<Track> = emptyList(),
        repeat: RepeatMode = this.repeat,
    ): Track? {
        context = tracks.filter { it.playable }
        this.index = index.coerceIn(-1, context.lastIndex)
        if (this.index < 0 && context.isNotEmpty()) this.index = 0
        this.manual = manual.filter { it.playable }.map { QueuedTrack(++qidCounter, it) }
        contextName = name
        contextKey = key
        this.shuffle = shuffle
        this.repeat = repeat
        originalOrder = source.takeIf { it.isNotEmpty() }
        current = context.getOrNull(this.index)?.let { NowPlaying(it) }
        return current?.track
    }

    /** Очередь от соседа по джему, без смены играющего трека. */
    fun applySnapshot(tracks: List<Track>?, index: Int?, manual: List<Track>?, shuffle: Boolean?, source: List<Track>?) {
        if (!tracks.isNullOrEmpty()) {
            context = tracks
            val i = index ?: tracks.indexOfFirst { it.id == current?.track?.id }
            this.index = i.coerceIn(0, tracks.lastIndex)
        }
        if (manual != null) this.manual = manual.map { QueuedTrack(++qidCounter, it) }
        if (shuffle != null) this.shuffle = shuffle
        if (source != null) originalOrder = source.takeIf { it.isNotEmpty() }
    }

    fun clear() {
        context = emptyList()
        index = -1
        manual = emptyList()
        current = null
        contextName = ""
        contextKey = ""
        originalOrder = null
    }
}
