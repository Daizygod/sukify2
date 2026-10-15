package ru.nepalimsya.sukify.playback.queue

import kotlin.random.Random
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.nepalimsya.sukify.domain.ArtistRef
import ru.nepalimsya.sukify.domain.Track

/** Поведение очереди один-в-один с frontend/src/stores/player.js. */
class QueueModelTest {

    private fun track(id: Long, playable: Boolean = true, artist: Long = id) = Track(
        id = id,
        title = "T$id",
        durationMs = 180_000,
        artists = persistentListOf(ArtistRef(artist, "A$artist", "a$artist")),
        release = null,
        cover = null,
        streamUrl = if (playable) "https://s3/audio/$id/stream.mp3" else null,
        audioRev = "rev$id",
        loudnessLufs = -10.0,
    )

    private val album = (1L..5L).map { track(it) }

    @Test
    fun startsFromChosenTrackSkippingSilentOnes() {
        val q = QueueModel()
        val tracks = listOf(track(1, playable = false), track(2), track(3))
        assertEquals(3L, q.playContext(tracks, tracks[2], "Альбом", "release:x")?.id)
        assertEquals(listOf(2L, 3L), q.context.map { it.id })
        assertEquals(1, q.index)
    }

    @Test
    fun manualQueuePlaysBeforeContext() {
        val q = QueueModel()
        q.playContext(album, album[0], "", "")
        q.addToQueue(track(99))
        assertEquals(99L, q.peekNext()?.id)
        assertEquals(99L, q.next(manualSkip = true)?.id)
        assertTrue(q.manual.isEmpty())
        // Контекст продолжается с того же места.
        assertEquals(2L, q.next(manualSkip = true)?.id)
    }

    @Test
    fun repeatModes() {
        val q = QueueModel()
        q.playContext(album, album[4], "", "")
        assertNull(q.peekNext())
        q.repeat = RepeatMode.All
        assertEquals(1L, q.peekNext()?.id)
        assertEquals(1L, q.next(manualSkip = false)?.id)
        q.repeat = RepeatMode.One
        // Доиграл — заново тот же; «Далее» — следующий.
        assertEquals(1L, q.next(manualSkip = false)?.id)
        assertEquals(2L, q.next(manualSkip = true)?.id)
    }

    @Test
    fun previousStopsAtStart() {
        val q = QueueModel()
        q.playContext(album, album[1], "", "")
        assertTrue(q.hasPrevious())
        assertEquals(1L, q.previous()?.id)
        assertFalse(q.hasPrevious())
        assertNull(q.previous())
    }

    @Test
    fun shuffleKeepsHeadAndRestoresOriginalOrder() {
        val q = QueueModel(Random(42))
        val many = (1L..30L).map { track(it) }
        q.playContext(many, many[9], "", "")
        q.setShuffle(true)
        assertEquals((1L..10L).toList(), q.context.take(10).map { it.id })
        assertEquals((11L..30L).toSet(), q.context.drop(10).map { it.id }.toSet())
        assertTrue(q.context.drop(10).map { it.id } != (11L..30L).toList())

        q.next(manualSkip = true)
        val playing = q.current?.track?.id
        q.setShuffle(false)
        assertEquals((1L..30L).toList(), q.context.map { it.id })
        assertEquals(playing, q.current?.track?.id)
        assertEquals(playing, q.context[q.index].id)
    }

    @Test
    fun smartShuffleAvoidsSameArtistBackToBack() {
        val q = QueueModel(Random(7))
        q.smartShuffle = true
        // Четыре исполнителя по пять треков.
        val tracks = (1L..20L).map { track(it, artist = (it - 1) / 5) }
        q.playContext(tracks, tracks[0], "", "")
        q.setShuffle(true)
        val artists = q.context.drop(1).map { it.artists.first().id }
        val repeats = artists.zipWithNext().count { (a, b) -> a == b }
        assertTrue("подряд один исполнитель $repeats раз(а)", repeats <= 3)
    }

    @Test
    fun upcomingEditing() {
        val q = QueueModel()
        q.playContext(album, album[0], "", "")
        q.removeUpcoming(0)
        assertEquals(listOf(3L, 4L, 5L), q.snapshot().upcoming.map { it.id })
        assertEquals(4L, q.playUpcoming(1)?.id)
        assertEquals(listOf(5L), q.snapshot().upcoming.map { it.id })
    }

    @Test
    fun playManualItemDoesNotMoveContextCursor() {
        val q = QueueModel()
        q.playContext(album, album[0], "", "")
        q.addToQueue(track(50))
        q.addToQueue(track(51))
        val second = q.manual[1]
        assertEquals(51L, q.playManual(second.qid)?.id)
        assertEquals(0, q.index)
        assertEquals(listOf(50L), q.manual.map { it.track.id })
        assertEquals(second.qid, q.current?.fromManualQid)
    }

    @Test
    fun restoreFromTransfer() {
        val q = QueueModel()
        val now = q.restore(album, index = 2, manual = listOf(track(9)), name = "Джем", key = "k")
        assertEquals(3L, now?.id)
        assertEquals(9L, q.peekNext()?.id)
        assertEquals("Джем", q.contextName)
    }
}
