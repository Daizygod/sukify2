package ru.nepalimsya.sukify.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.toImmutableList
import ru.nepalimsya.sukify.data.model.SearchDto
import ru.nepalimsya.sukify.data.prefs.RecentSearch
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.MediaKind
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.domain.toMedia
import ru.nepalimsya.sukify.ui.components.CardPair
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.FilterChips
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.ShelfHeader
import ru.nepalimsya.sukify.ui.components.TrackRow
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.menus.SearchField
import ru.nepalimsya.sukify.ui.shell.GenreRoute
import ru.nepalimsya.sukify.ui.shell.LocalDrawer
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.shell.SectionRoute
import ru.nepalimsya.sukify.ui.shell.UserAvatar
import ru.nepalimsya.sukify.ui.shell.bottomChromePadding
import ru.nepalimsya.sukify.ui.shell.route
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.ui.theme.parseHexColor

private val TileColors = listOf("#e13300", "#1e3264", "#8d67ab", "#e8115b", "#148a08", "#503750", "#477d95", "#ba5d07", "#0d73ec", "#af2896")

private enum class ResultChip(val label: String) {
    All("Все"), Tracks("Треки"), Artists("Исполнители"), Albums("Альбомы"), Playlists("Плейлисты"), Users("Пользователи")
}

private val ReleaseSorts = listOf("added" to "Недавно в Sukify", "date" to "Дата выхода", "name" to "По алфавиту")

/** Поиск (SearchPage.vue): обзор жанров и каталога или результаты запроса. */
@Composable
fun SearchScreen(vm: SearchViewModel = hiltViewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    var focused by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    BackHandler(enabled = focused) {
        focused = false
        vm.setQuery("")
        focus.clearFocus()
    }

    AnimatedContent(
        targetState = focused,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "search",
    ) { isFocused ->
        if (isFocused) {
            FocusedSearch(
                vm = vm,
                query = query,
                onCancel = {
                    focused = false
                    vm.setQuery("")
                    focus.clearFocus()
                },
            )
        } else {
            Browse(vm, onOpenSearch = { focused = true })
        }
    }
}

// --- Обзор --------------------------------------------------------------------

@Composable
private fun Browse(vm: SearchViewModel, onOpenSearch: () -> Unit) {
    val genres by vm.genres.collectAsStateWithLifecycle()
    val page by vm.catalogPage.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle(null)
    val navigator = LocalNavigator.current
    val menus = LocalMenus.current
    val listState = rememberLazyListState()

    // Догружаем каталог, когда до конца списка осталось несколько строк.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, page.items.size) { if (nearEnd && page.hasMore) vm.loadMore() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(SukifyColors.Elevated),
        contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp)),
    ) {
        item(key = "head") {
            Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(user, size = 34.dp, onClick = LocalDrawer.current::open)
                    Spacer(Modifier.width(12.dp))
                    Text("Поиск", style = SukifyType.PageTitle, color = SukifyColors.Text)
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SukifyColors.Text)
                        .bounceClick(pressed = 0.98f, onClick = onOpenSearch)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(SukifyIcons.Search, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Что хочешь включить?", style = SukifyType.BodyBold, color = Color(0xFF535353))
                }
            }
        }
        item(key = "genres-title") {
            Text(
                "Все остальное",
                style = SukifyType.SectionTitle,
                color = SukifyColors.Text,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            )
        }
        val tiles = buildList {
            genres.dataOrNull.orEmpty().forEachIndexed { i, g -> add(Tile(g.genre, TileColors[i % TileColors.size], SukifyIcons.Album, GenreRoute(g.genre))) }
            val n = genres.dataOrNull.orEmpty().size
            add(Tile("Новые релизы", TileColors[n % TileColors.size], SukifyIcons.Nowplaying, SectionRoute("new")))
            add(Tile("Для тебя", TileColors[(n + 1) % TileColors.size], SukifyIcons.HeartFill, SectionRoute("popular")))
        }
        items(tiles.chunked(2), key = { row -> "tiles:" + row.first().title }) { row ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile -> GenreTile(tile, Modifier.weight(1f)) { navigator.open(tile.route) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item(key = "all-head") {
            Column {
                ShelfHeader("Все релизы")
                FilterChips(ReleaseSorts, page.sort, { it?.let(vm::setSort) }, Modifier.padding(bottom = 8.dp))
            }
        }
        items(page.items.chunked(2), key = { row -> "rel:" + row.first().key }) { row ->
            CardPair(row[0], row.getOrNull(1), onOpen = navigator::open, onLongPress = { menus.entity(it) })
        }
        if (page.loading) item(key = "loading") { LoadingBlock() }
    }
}

private data class Tile(val title: String, val color: String, val icon: ImageVector, val route: Any)

@Composable
private fun GenreTile(tile: Tile, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(96.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(parseHexColor(tile.color) ?: SukifyColors.CardPressed)
            .bounceClick(pressed = 0.96f, onClick = onClick),
    ) {
        Text(
            tile.title,
            style = SukifyType.BodyBold,
            color = SukifyColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(12.dp).padding(end = 28.dp),
        )
        Icon(
            tile.icon,
            null,
            tint = Color.Black.copy(alpha = 0.28f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 14.dp, y = 8.dp)
                .size(64.dp)
                .rotate(25f),
        )
    }
}

// --- Поиск --------------------------------------------------------------------

@Composable
private fun FocusedSearch(vm: SearchViewModel, query: String, onCancel: () -> Unit) {
    val results by vm.results.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    var chip by rememberSaveable { mutableStateOf(ResultChip.All) }
    LaunchedEffect(results.query) { chip = ResultChip.All }

    Column(Modifier.fillMaxSize().background(SukifyColors.Elevated).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(query, vm::setQuery, "Что хочешь включить?", Modifier.weight(1f).testTag("search_field"), autoFocus = true)
            Text(
                "Отмена",
                style = SukifyType.BodySmallBold,
                color = SukifyColors.Text,
                modifier = Modifier.bounceClick(pressed = 0.94f, onClick = onCancel).padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        val data = results.data
        when {
            query.isBlank() -> RecentList(vm, recent)
            data == null && results.loading -> LoadingBlock()
            data == null && results.error != null -> Text(results.error.orEmpty(), style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, modifier = Modifier.padding(16.dp))
            data != null -> {
                val chips = ResultChip.entries.filter { c ->
                    when (c) {
                        ResultChip.All -> true
                        ResultChip.Tracks -> data.tracks.isNotEmpty()
                        ResultChip.Artists -> data.artists.isNotEmpty()
                        ResultChip.Albums -> data.releases.isNotEmpty()
                        ResultChip.Playlists -> data.playlists.isNotEmpty()
                        ResultChip.Users -> data.users.isNotEmpty()
                    }
                }.map { it to it.label }
                FilterChips(chips, chip, { chip = it ?: ResultChip.All }, Modifier.padding(bottom = 8.dp))
                Results(vm, results.query, data, chip)
            }
        }
    }
}

@Composable
private fun RecentList(vm: SearchViewModel, recent: List<RecentSearch>) {
    val navigator = LocalNavigator.current
    val playback = LocalPlayback.current
    if (recent.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 80.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Включи то, что любишь", style = SukifyType.Title, color = SukifyColors.Text)
            Spacer(Modifier.height(8.dp))
            Text("Ищи исполнителей, треки, альбомы, плейлисты и людей.", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp))) {
        item {
            Text("Недавние запросы", style = SukifyType.BodyBold, color = SukifyColors.Text, modifier = Modifier.padding(16.dp))
        }
        items(recent, key = { it.key }) { entry ->
            ResultRow(
                title = entry.title,
                subtitle = entry.subtitle,
                cover = Cover.single(entry.image),
                round = entry.round,
                onClick = {
                    val track = entry.track
                    if (track != null) {
                        val t = track.toDomain()
                        playback.contexts.playFrom(listOf(t), t, "Поиск", "")
                    } else {
                        val kind = runCatching { MediaKind.valueOf(entry.kind) }.getOrNull() ?: return@ResultRow
                        navigator.open(MediaItem(kind, entry.ref, entry.title, entry.subtitle, null).route())
                    }
                },
                trailing = {
                    Icon(
                        SukifyIcons.Close,
                        "Убрать",
                        tint = SukifyColors.TextSubdued,
                        modifier = Modifier.size(40.dp).bounceClick(pressed = 0.85f) { vm.forget(entry) }.padding(12.dp),
                    )
                },
            )
        }
        item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Очистить историю поиска",
                    style = SukifyType.BodySmallBold,
                    color = SukifyColors.Text,
                    modifier = Modifier
                        .bounceClick(pressed = 0.95f, onClick = vm::clearRecent)
                        .background(Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Results(vm: SearchViewModel, query: String, data: SearchDto, chip: ResultChip) {
    val navigator = LocalNavigator.current
    val playback = LocalPlayback.current
    val menus = LocalMenus.current
    val tracks = remember(data) { data.tracks.map { it.toDomain() }.toImmutableList() }
    val open: (MediaItem) -> Unit = { item ->
        vm.remember(item)
        navigator.open(item.route())
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp))) {
        fun LazyListScope.trackRows(list: List<ru.nepalimsya.sukify.domain.Track>) {
            items(list, key = { "t:${it.id}" }) { track ->
                TrackRow(
                    track = track,
                    playing = playback.playingTrackId == track.id,
                    onClick = {
                        vm.remember(track, data.tracks.firstOrNull { it.id == track.id })
                        playback.contexts.playFrom(tracks, track, "Поиск", "")
                    },
                    onMore = { menus.track(track) },
                    onSwipeQueue = { playback.contexts.enqueueTracks(listOf(track)) },
                )
            }
        }
        fun LazyListScope.mediaRows(list: List<MediaItem>, prefix: String) {
            items(list, key = { "$prefix:${it.key}" }) { item ->
                ResultRow(item.title, item.subtitle, item.cover, item.round, onClick = { open(item) }, onLongClick = { menus.entity(item) })
            }
        }

        val artists = data.artists.map { it.toMedia() }
        val albums = data.releases.map { it.toMedia("Альбом • ${it.artist?.name.orEmpty()}") }
        val playlists = data.playlists.map { it.toMedia("Плейлист • ${it.owner?.name.orEmpty()}") }
        val users = data.users.map { it.toMedia() }

        when (chip) {
            ResultChip.All -> {
                // Топ-результат: исполнитель при точном попадании, иначе первый трек (как в вебе).
                val q = query.lowercase()
                val topArtist = artists.firstOrNull()?.takeIf { it.title.lowercase().contains(q) }
                if (topArtist != null) mediaRows(listOf(topArtist), "top")
                trackRows(tracks.take(4))
                mediaRows(artists.filterNot { it == topArtist }.take(3), "a")
                mediaRows(albums.take(3), "al")
                mediaRows(playlists.take(3), "p")
                mediaRows(users.take(3), "u")
            }
            ResultChip.Tracks -> trackRows(tracks)
            ResultChip.Artists -> mediaRows(artists, "a")
            ResultChip.Albums -> mediaRows(albums, "al")
            ResultChip.Playlists -> mediaRows(playlists, "p")
            ResultChip.Users -> mediaRows(users, "u")
        }
        val empty = data.tracks.isEmpty() && data.artists.isEmpty() && data.releases.isEmpty() && data.playlists.isEmpty() && data.users.isEmpty()
        if (empty) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 64.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ничего не нашлось по запросу «$query»", style = SukifyType.Title, color = SukifyColors.Text)
                    Spacer(Modifier.height(8.dp))
                    Text("Проверь написание или попробуй другие слова.", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    cover: Cover?,
    round: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            cover,
            size = 48.dp,
            shape = if (round) CircleShape else RoundedCornerShape(2.dp),
            fallbackIcon = if (round) SukifyIcons.Person else SukifyIcons.Album,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SukifyType.Body, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

