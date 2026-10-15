package ru.nepalimsya.sukify.domain

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class LyricLine(val ms: Long, val text: String)

/** Текст трека: синхронный (строки с таймкодами) или просто текст. */
@Immutable
data class Lyrics(
    val synced: ImmutableList<LyricLine> = persistentListOf(),
    val plain: String? = null,
) {
    val hasAny: Boolean get() = synced.isNotEmpty() || !plain.isNullOrBlank()

    /** Строки для показа: синхронные или разбитый на строки обычный текст. */
    val displayLines: List<String>
        get() = if (synced.isNotEmpty()) synced.map { it.text.ifBlank { "♪" } } else plain.orEmpty().lines()

    /** Номер строки, которая звучит в момент positionMs (-1 — ещё не началась). */
    fun activeIndex(positionMs: Long): Int {
        if (synced.isEmpty()) return -1
        // Бинарный поиск: вызывается на каждом кадре полноэкранного текста.
        var lo = 0
        var hi = synced.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (synced[mid].ms <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        private val stamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
        private val anyTag = Regex("""\[.*?]""")

        /**
         * LRC → строки по времени (порт parseLrc из useLyrics.js). Строка может
         * нести несколько таймкодов — тогда она повторяется для каждого.
         */
        fun parse(synced: String?, plain: String?): Lyrics {
            val lines = mutableListOf<LyricLine>()
            synced.orEmpty().lineSequence().forEach { raw ->
                val stamps = stamp.findAll(raw).toList()
                if (stamps.isEmpty()) return@forEach
                val text = raw.replace(anyTag, "").trim()
                for (s in stamps) {
                    val minutes = s.groupValues[1].toLong()
                    val seconds = s.groupValues[2].toDouble()
                    lines += LyricLine(((minutes * 60 + seconds) * 1000).toLong(), text)
                }
            }
            return Lyrics(lines.sortedBy { it.ms }.toImmutableList(), plain?.takeIf { it.isNotBlank() })
        }
    }
}
