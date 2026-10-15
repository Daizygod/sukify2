package ru.nepalimsya.sukify.ui.home

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.datastore.SessionStore
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.model.DailyMixDto
import ru.nepalimsya.sukify.data.model.HomeDto
import ru.nepalimsya.sukify.data.model.UserDto
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.MediaKind
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.domain.toMedia
import ru.nepalimsya.sukify.ui.collections.LikedBrush
import ru.nepalimsya.sukify.ui.collections.LikedItem
import ru.nepalimsya.sukify.ui.common.ScreenData
import ru.nepalimsya.sukify.ui.components.Chip
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.ErrorBlock
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.Shelf
import ru.nepalimsya.sukify.ui.components.pressScale
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.LocalDrawer
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.shell.ReleaseRoute
import ru.nepalimsya.sukify.ui.shell.ReleasesRoute
import ru.nepalimsya.sukify.ui.shell.SectionRoute
import ru.nepalimsya.sukify.ui.shell.UserAvatar
import ru.nepalimsya.sukify.ui.shell.bottomChromePadding
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    library: LibraryState,
    sessions: SessionStore,
) : ViewModel() {
    private val home = ScreenData(viewModelScope, catalog.peek("home")) { catalog.home() }
    private val mixes = ScreenData(viewModelScope, catalog.peek("mixes")) { catalog.dailyMixes() }

    val state: StateFlow<Loadable<HomeDto>> = home.state
    val mixesState: StateFlow<Loadable<List<DailyMixDto>>> = mixes.state
    val playlists = library.playlists
    val libraryReady = library.ready
    val user = sessions.user

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refresh() {
        _refreshing.value = true
        home.reload()
        mixes.reload()
        viewModelScope.launch {
            kotlinx.coroutines.delay(700)
            _refreshing.value = false
        }
    }
}

/** Главная (HomePage.vue в мобильной раскладке). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val mixes by vm.mixesState.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val libraryReady by vm.libraryReady.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle(null)
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    // Пока медиатека не поднялась с диска, главная ждёт целиком: один кадр
    // спиннера лучше, чем плитки, которые сдвигаются под пальцем.
    val home = state.dataOrNull?.takeIf { libraryReady }
    // «Полностью отрисовано» — когда главная с данными: по этой метке меряют холодный старт.
    ReportDrawnWhen { home != null || state is Loadable.Failed }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        HomeContent(
            home = home,
            failed = (state as? Loadable.Failed)?.message,
            mixes = mixes.dataOrNull.orEmpty(),
            shortcuts = remember(playlists, home) { if (home == null) emptyList() else shortcuts(playlists.map { it.toMedia() }, home) },
            user = user,
            onRetry = vm::refresh,
        )
    }
}

/** Сетка 2×4: «Любимые треки», плейлисты, добивка новыми релизами (как в вебе). */
private fun shortcuts(playlists: List<MediaItem>, home: HomeDto?): List<MediaItem> {
    val tiles = ArrayList<MediaItem>(8)
    tiles += LikedItem
    tiles += playlists
    for (release in home?.newReleases.orEmpty()) {
        if (tiles.size >= 8) break
        tiles += release.toMedia()
    }
    return tiles.take(8)
}

@Composable
fun HomeContent(
    home: HomeDto?,
    failed: String?,
    mixes: List<DailyMixDto>,
    shortcuts: List<MediaItem>,
    user: UserDto?,
    onRetry: () -> Unit,
) {
    val navigator = LocalNavigator.current
    val menus = LocalMenus.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var chip by rememberSaveable { mutableStateOf(0) }

    Box(Modifier.fillMaxSize().background(SukifyColors.Elevated)) {
        // Сиреневая подсветка сверху, как .home__tint в вебе.
        Box(
            Modifier
                .fillMaxWidth()
                .height(330.dp + statusTop)
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xD9534784),
                        0.55f to Color(0x732B2642),
                        1f to Color.Transparent,
                    ),
                ),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("home_list"),
            contentPadding = PaddingValues(top = statusTop + 12.dp, bottom = bottomChromePadding(24.dp)),
        ) {
            item(key = "chips") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UserAvatar(user, size = 34.dp, onClick = LocalDrawer.current::open)
                    Chip("Все", selected = chip == 0, onClick = { chip = 0 })
                    Chip("Музыка", selected = chip == 1, onClick = { chip = 1 })
                }
            }
            if (shortcuts.isNotEmpty()) {
                item(key = "shortcuts") {
                    ShortcutGrid(
                        tiles = shortcuts,
                        onOpen = navigator::open,
                        onLongPress = { menus.entity(it) },
                    )
                }
            }
            if (home == null) {
                item(key = "state") {
                    if (failed != null) ErrorBlock(failed, onRetry) else LoadingBlock()
                }
                return@LazyColumn
            }
            if (mixes.isNotEmpty()) {
                item(key = "mixes") {
                    Shelf(
                        title = "Создано для тебя",
                        items = mixes.map { it.toMedia() },
                        onOpen = navigator::open,
                        onLongPress = { menus.entity(it) },
                    )
                }
            }
            if (home.recentlyPlayed.isNotEmpty()) {
                item(key = "recent") {
                    TrackShelf("Ты недавно слушал(-а)", home.recentlyPlayed.take(8).map { it.toDomain() }, onShowAll = { navigator.open(SectionRoute("recent")) })
                }
            }
            if (home.popularTracks.isNotEmpty()) {
                item(key = "popular") {
                    TrackShelf(
                        title = user?.name ?: "Популярно сейчас",
                        eyebrow = if (user != null) "Только для тебя," else null,
                        tracks = home.popularTracks.take(8).map { it.toDomain() },
                        onShowAll = { navigator.open(SectionRoute("popular")) },
                    )
                }
            }
            if (home.newReleases.isNotEmpty()) {
                item(key = "new") {
                    Shelf(
                        title = "Новые релизы",
                        items = home.newReleases.take(8).map { it.toMedia() },
                        onOpen = navigator::open,
                        onShowAll = { navigator.open(SectionRoute("new")) },
                        onLongPress = { menus.entity(it) },
                    )
                }
            }
            if (home.recentlyAdded.isNotEmpty()) {
                item(key = "added") {
                    Shelf(
                        title = "Недавно в Sukify",
                        items = home.recentlyAdded.take(8).map { it.toMedia() },
                        onOpen = navigator::open,
                        onShowAll = { navigator.open(ReleasesRoute()) },
                        onLongPress = { menus.entity(it) },
                    )
                }
            }
        }
    }
}

/**
 * Полка треков: карточка ведёт на релиз трека (как MediaCard в вебе), долгое
 * нажатие — меню трека.
 */
@Composable
private fun TrackShelf(title: String, tracks: List<Track>, onShowAll: () -> Unit, eyebrow: String? = null) {
    val navigator = LocalNavigator.current
    val menus = LocalMenus.current
    val playback = LocalPlayback.current
    val items = tracks.map { t ->
        MediaItem(
            kind = MediaKind.Release,
            ref = t.release?.slug.orEmpty(),
            title = t.title,
            subtitle = t.artistNames,
            cover = t.cover,
            id = t.id,
        )
    }
    Shelf(
        title = title,
        eyebrow = eyebrow,
        items = items,
        onOpen = { item ->
            val track = tracks.first { it.id == item.id }
            if (track.release != null) navigator.open(ReleaseRoute(track.release.slug))
            else playback.contexts.playFrom(tracks, track, title, "")
        },
        onShowAll = onShowAll,
        onLongPress = { item -> menus.track(tracks.first { it.id == item.id }) },
    )
}

/** Сетка быстрого доступа: плитка — обложка + название; играет — зелёная метка. */
@Composable
private fun ShortcutGrid(tiles: List<MediaItem>, onOpen: (MediaItem) -> Unit, onLongPress: (MediaItem) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile ->
                    Shortcut(tile, Modifier.weight(1f), onOpen, onLongPress)
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Shortcut(tile: MediaItem, modifier: Modifier, onOpen: (MediaItem) -> Unit, onLongPress: (MediaItem) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val playing = LocalPlayback.current.isPlaying(tile.contextKey)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(56.dp)
            .pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onOpen(tile) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress(tile)
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tile.kind == MediaKind.Liked) {
            Box(Modifier.size(56.dp).background(LikedBrush), contentAlignment = Alignment.Center) {
                Icon(SukifyIcons.HeartFill, null, tint = SukifyColors.Text, modifier = Modifier.size(20.dp))
            }
        } else {
            CoverImage(tile.cover, size = 56.dp, shape = RoundedCornerShape(0.dp))
        }
        Text(
            tile.title,
            style = SukifyType.LabelBold.copy(fontSize = 13.sp),
            color = SukifyColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        if (playing) {
            Icon(SukifyIcons.Nowplaying, "Играет", tint = SukifyColors.Accent, modifier = Modifier.padding(end = 8.dp).size(14.dp))
        }
    }
}

/** Приветствие по времени суток, как в приложении Spotify. */
fun greeting(now: LocalTime = LocalTime.now()): String = when (now.hour) {
    in 5..11 -> "Доброе утро"
    in 12..17 -> "Добрый день"
    in 18..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}
