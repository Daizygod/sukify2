package ru.nepalimsya.sukify.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.data.model.ColorsDto
import ru.nepalimsya.sukify.data.model.DailyMixDto
import ru.nepalimsya.sukify.data.model.HomeDto
import ru.nepalimsya.sukify.data.model.ReleaseDto
import ru.nepalimsya.sukify.data.model.TrackDto
import ru.nepalimsya.sukify.data.model.UserDto
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.domain.toMedia
import ru.nepalimsya.sukify.playback.ContextPlayer
import ru.nepalimsya.sukify.playback.PlayerState
import ru.nepalimsya.sukify.ui.artist.DiscoTab
import ru.nepalimsya.sukify.ui.artist.artistContent
import ru.nepalimsya.sukify.ui.collections.LikedItem
import ru.nepalimsya.sukify.ui.components.CollectionScaffold
import ru.nepalimsya.sukify.ui.home.HomeContent
import ru.nepalimsya.sukify.ui.release.releaseContent
import ru.nepalimsya.sukify.ui.player.MiniPlayer
import ru.nepalimsya.sukify.ui.shell.BottomBar
import ru.nepalimsya.sukify.ui.shell.BottomBarHeight
import ru.nepalimsya.sukify.ui.shell.LocalBottomChrome
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.shell.MenuController
import ru.nepalimsya.sukify.ui.shell.Navigator
import ru.nepalimsya.sukify.ui.shell.PlaybackUi
import ru.nepalimsya.sukify.ui.shell.Tab
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyTheme
import ru.nepalimsya.sukify.ui.theme.parseHexColor

/** Кнопки «играть подборку» в тестах ничего не делают. */
private object NoopPlayer : ContextPlayer {
    override fun toggle(key: String, name: String, tracks: List<Track>?, shuffle: Boolean) = Unit

    override fun playFrom(tracks: List<Track>, start: Track, name: String, key: String) = Unit

    override fun enqueue(key: String) = Unit

    override fun enqueueTracks(tracks: List<Track>) = Unit
}

/** Всё, что экраны берут из оболочки: плеер, меню, навигация, отступ снизу. */
@Composable
private fun TestShell(miniPlayer: Boolean = false, content: @Composable () -> Unit) {
    val nav = rememberNavController()
    SukifyTheme {
        CompositionLocalProvider(
            LocalPlayback provides remember { PlaybackUi(mutableStateOf(PlayerState()), NoopPlayer) },
            LocalMenus provides remember { MenuController() },
            LocalNavigator provides remember(nav) { Navigator(nav) },
            LocalBottomChrome provides if (miniPlayer) 140.dp else 72.dp,
        ) {
            Box(Modifier.fillMaxSize().background(SukifyColors.Elevated)) {
                content()
                BottomBar(Tab.Home, onSelect = {}, onCreate = {}, modifier = Modifier.align(Alignment.BottomCenter))
                if (miniPlayer) {
                    val tracks = Fixtures.tracks
                    MiniPlayer(
                        track = tracks[1],
                        previous = tracks[0],
                        next = tracks[2],
                        playing = true,
                        liked = true,
                        duration = 200_000,
                        position = { 64_000 },
                        remoteDevice = null,
                        onOpen = {},
                        onTogglePlay = {},
                        onToggleLike = {},
                        onNext = {},
                        onPrevious = {},
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = BottomBarHeight + 4.dp),
                    )
                }
            }
        }
    }
}

private val paperKites = ArtistDto(3, "The Paper Kites", "the-paper-kites", colors = ColorsDto("#4a3b2a"), monthlyListeners = 7_083_639)

private fun trackDto(id: Long, title: String, plays: Long = 0) = TrackDto(
    id = id,
    title = title,
    durationMs = 180_000 + id * 11_000,
    streamUrl = "https://s3/audio/$id/stream.mp3",
    audioRev = "rev$id",
    artists = listOf(paperKites),
    playsCount = plays,
)

private val goldenHour = ReleaseDto(
    id = 5,
    title = "Golden Hour",
    slug = "the-paper-kites-golden-hour",
    type = "album",
    releaseDate = "2023-10-20",
    year = 2023,
    colors = ColorsDto("#A28A68"),
    artist = paperKites,
    tracks = listOf(
        trackDto(18, "Fields of Gold"),
        trackDto(19, "Golden Hour"),
        trackDto(20, "Paper Moon"),
        trackDto(21, "Slow Light"),
        trackDto(22, "Riverbank"),
    ),
)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7, application = Application::class)
class CatalogScreenshotTest {

    @Test
    fun home() {
        val home = HomeDto(
            recentlyPlayed = goldenHour.tracks.orEmpty().take(4).map { it.copy(release = goldenHour) },
            popularTracks = goldenHour.tracks.orEmpty().map { it.copy(release = goldenHour) },
            newReleases = listOf(goldenHour, goldenHour.copy(id = 6, slug = "static", title = "Static", year = 2024)),
            recentlyAdded = listOf(goldenHour),
        )
        val mixes = listOf(
            DailyMixDto(1, "Микс дня #1", "Инди-фолк", 30, listOf("The Paper Kites", "Luna Waves"), "#7b4f9d"),
            DailyMixDto(2, "Микс дня #2", "Хип-хоп", 30, listOf("Metro Boomin"), "#1e6b52"),
        )
        captureRoboImage("screenshots/home.png") {
            TestShell(miniPlayer = true) {
                HomeContent(
                    home = home,
                    failed = null,
                    mixes = mixes,
                    shortcuts = listOf(LikedItem, goldenHour.toMedia(), goldenHour.copy(id = 7, slug = "x", title = "HEROES & VILLAINS").toMedia()),
                    user = UserDto(2, "daizygod", "daizygod"),
                    onRetry = {},
                )
            }
        }
    }

    @Test
    fun release() {
        val color = parseHexColor(goldenHour.colors?.background)!!
        captureRoboImage("screenshots/release.png") {
            TestShell {
                CollectionScaffold(color = color, title = goldenHour.title) { hero ->
                    releaseContent(goldenHour, color, hero, liked = true, onLike = {})
                }
            }
        }
    }

    @Test
    fun artist() {
        val top = goldenHour.tracks.orEmpty().mapIndexed { i, t -> t.copy(playsCount = 4_000_000L - i * 610_000) }
        val artist = paperKites.copy(
            releases = listOf(goldenHour, goldenHour.copy(id = 9, slug = "single", title = "Midnight Sun", type = "single", year = 2024)),
            bio = "Австралийская инди-фолк группа из Мельбурна.",
        )
        captureRoboImage("screenshots/artist.png") {
            TestShell {
                CollectionScaffold(color = parseHexColor("#4a3b2a")!!, title = artist.name) { hero ->
                    artistContent(
                        artist = artist,
                        color = parseHexColor("#4a3b2a")!!,
                        heroModifier = hero,
                        top = top.map { it.toDomain(goldenHour) },
                        plays = top.associate { it.id to (it.playsCount ?: 0) },
                        liked = null,
                        following = false,
                        expanded = false,
                        onToggleExpanded = {},
                        tab = DiscoTab.Popular,
                        onTab = {},
                        onFollow = {},
                    )
                }
            }
        }
    }
}

