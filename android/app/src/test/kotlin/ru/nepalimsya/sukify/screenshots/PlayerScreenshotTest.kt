package ru.nepalimsya.sukify.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.PlayerState
import ru.nepalimsya.sukify.playback.queue.NowPlaying
import ru.nepalimsya.sukify.playback.queue.QueueSnapshot
import ru.nepalimsya.sukify.playback.queue.QueuedTrack
import ru.nepalimsya.sukify.playback.queue.RepeatMode
import ru.nepalimsya.sukify.ui.player.LyricsScreen
import ru.nepalimsya.sukify.ui.player.NowPlayingActions
import ru.nepalimsya.sukify.ui.player.NowPlayingScreen
import ru.nepalimsya.sukify.ui.player.QueueContent
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyTheme

private object NoActions : NowPlayingActions {
    override fun close() = Unit
    override fun togglePlay() = Unit
    override fun next() = Unit
    override fun previous(force: Boolean) = Unit
    override fun seek(ms: Long) = Unit
    override fun toggleShuffle() = Unit
    override fun cycleRepeat() = Unit
    override fun toggleLike(track: Track) = Unit
    override fun openQueue() = Unit
    override fun openDevices() = Unit
    override fun openLyrics() = Unit
    override fun openArtist(slug: String) = Unit
    override fun toggleFollow(artist: ArtistDto) = Unit
    override fun openMenu(track: Track) = Unit
}

/** Скриншоты плеера: «Сейчас играет», очередь, текст на весь экран. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class PlayerScreenshotTest {

    private val tracks = Fixtures.tracks

    private val state = PlayerState(
        queue = QueueSnapshot(
            current = NowPlaying(tracks[1]),
            context = tracks.toImmutableList(),
            index = 1,
            manual = persistentListOf(QueuedTrack(1, tracks[5])),
            contextName = "HEROES & VILLAINS",
            contextKey = "release:heroes-villains",
            shuffle = true,
            repeat = RepeatMode.One,
        ),
        playing = true,
        durationMs = 201_000,
    )

    private val artist = ArtistDto(
        id = 1,
        name = "Metro Boomin",
        slug = "metro-boomin",
        monthlyListeners = 45_123_456,
        isFollowed = false,
    )

    @Test
    fun nowPlaying() {
        captureRoboImage("screenshots/now_playing.png") {
            SukifyTheme {
                NowPlayingScreen(
                    state = state,
                    liked = true,
                    lyrics = Fixtures.lyrics,
                    artist = artist,
                    remoteDevice = null,
                    position = { 10_500 },
                    actions = NoActions,
                )
            }
        }
    }

    @Test
    fun queue() {
        captureRoboImage("screenshots/queue.png") {
            SukifyTheme {
                Box(Modifier.fillMaxSize().background(SukifyColors.Elevated)) {
                    QueueContent(state, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
    }

    @Test
    fun lyricsFullscreen() {
        captureRoboImage("screenshots/lyrics.png") {
            SukifyTheme {
                LyricsScreen(
                    track = tracks[1],
                    lyrics = Fixtures.lyrics,
                    playing = true,
                    duration = 201_000,
                    position = { 14_000 },
                    onSeek = {},
                    onTogglePlay = {},
                    onClose = {},
                )
            }
        }
    }
}
