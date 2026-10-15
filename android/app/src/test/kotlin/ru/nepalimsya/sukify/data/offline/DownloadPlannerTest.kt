package ru.nepalimsya.sukify.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.nepalimsya.sukify.data.offline.DownloadPlanner.Audio

class DownloadPlannerTest {

    private val library = ManifestLibrary(playlists = listOf(1, 2), albums = listOf(5), artists = listOf(3))
    private val available = setOf("liked", "playlist:1", "playlist:2", "release:5", "release:9", "artist:3")

    @Test
    fun `nothing is selected by default`() {
        assertEquals(emptySet<String>(), DownloadPlanner.selectedKeys(DownloadSettings(), library, available))
    }

    @Test
    fun `auto categories expand to library collections`() {
        val settings = DownloadSettings(autoLiked = true, autoPlaylists = true)
        assertEquals(setOf("liked", "playlist:1", "playlist:2"), DownloadPlanner.selectedKeys(settings, library, available))
    }

    @Test
    fun `manual includes add and excludes remove`() {
        val settings = DownloadSettings(autoPlaylists = true, includes = setOf("release:9"), excludes = setOf("playlist:2"))
        assertEquals(setOf("playlist:1", "release:9"), DownloadPlanner.selectedKeys(settings, library, available))
    }

    @Test
    fun `collections missing from manifest are ignored`() {
        val settings = DownloadSettings(includes = setOf("playlist:404"))
        assertTrue(DownloadPlanner.selectedKeys(settings, library, available).isEmpty())
    }

    @Test
    fun `shared track is wanted once and silent tracks are skipped`() {
        val desired = DownloadPlanner.desired(
            selected = setOf("liked", "playlist:1"),
            sourceTracks = mapOf("liked" to listOf(10, 11), "playlist:1" to listOf(11, 12)),
            audio = mapOf(10L to Audio("a", 1, false), 11L to Audio("b", 2, false)),
            previews = true,
        )
        assertEquals(listOf(10L, 11L), desired.keys.toList())
    }

    @Test
    fun `previews can be turned off`() {
        val desired = DownloadPlanner.desired(
            selected = setOf("liked"),
            sourceTracks = mapOf("liked" to listOf(10, 11)),
            audio = mapOf(10L to Audio("a", 1, preview = true), 11L to Audio("b", 2, preview = false)),
            previews = false,
        )
        assertEquals(setOf(11L), desired.keys)
    }

    @Test
    fun `diff queues new, requeues changed revision and removes unwanted`() {
        val current = listOf(
            DownloadEntity(trackId = 1, rev = "old", size = 10, state = DownloadState.DONE, fileRev = "old", path = "/a"),
            DownloadEntity(trackId = 2, rev = "same", size = 20, state = DownloadState.DONE, fileRev = "same", path = "/b"),
            DownloadEntity(trackId = 3, rev = "x", size = 30, state = DownloadState.DONE, fileRev = "x", path = "/c"),
        )
        val desired = mapOf(
            1L to Audio("new", 11, false),
            2L to Audio("same", 20, false),
            4L to Audio("fresh", 40, false),
        )
        val diff = DownloadPlanner.diff(desired, current, now = 1000)

        val byId = diff.enqueue.associateBy { it.trackId }
        assertEquals(setOf(1L, 4L), byId.keys)
        // Сменилась ревизия: в очередь, но старый файл пока остаётся и играет.
        assertEquals("new", byId.getValue(1).rev)
        assertEquals("old", byId.getValue(1).fileRev)
        assertEquals("/a", byId.getValue(1).path)
        assertEquals(DownloadState.QUEUED, byId.getValue(1).state)
        assertEquals(DownloadState.QUEUED, byId.getValue(4).state)
        // Трек 3 больше не нужен (удалён в Sukify или снят лайк).
        assertEquals(listOf(3L), diff.remove.map { it.trackId })
    }

    @Test
    fun `unchanged library produces empty diff`() {
        val current = listOf(DownloadEntity(trackId = 1, rev = "r", size = 1, state = DownloadState.DONE, fileRev = "r"))
        assertTrue(DownloadPlanner.diff(mapOf(1L to Audio("r", 1, false)), current).isEmpty)
    }
}
