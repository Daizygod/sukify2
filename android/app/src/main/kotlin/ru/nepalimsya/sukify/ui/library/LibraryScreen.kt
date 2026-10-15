package ru.nepalimsya.sukify.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.datastore.SessionStore
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.prefs.UiPrefs
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.MediaKind
import ru.nepalimsya.sukify.domain.releaseKindLabel
import ru.nepalimsya.sukify.domain.toMedia
import ru.nepalimsya.sukify.ui.collections.LikedBrush
import ru.nepalimsya.sukify.ui.collections.LikedItem
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.FillCover
import ru.nepalimsya.sukify.ui.components.FilterChips
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.downloads.LocalDownloads
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.menus.NameDialog
import ru.nepalimsya.sukify.ui.menus.SearchField
import ru.nepalimsya.sukify.ui.shell.LocalDrawer
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.shell.PlaylistRoute
import ru.nepalimsya.sukify.ui.shell.UserAvatar
import ru.nepalimsya.sukify.ui.shell.bottomChromePadding
import ru.nepalimsya.sukify.ui.shell.route
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.util.Format
import javax.inject.Inject

enum class LibraryFilter(val label: String) { Playlists("Плейлисты"), Artists("Исполнители"), Albums("Альбомы"), Downloaded("Скачано") }

enum class LibrarySort(val key: String, val label: String) {
    Recent("recent", "Недавние"),
    Added("added", "Недавно добавленные"),
    Alpha("alpha", "По алфавиту"),
    Creator("creator", "По автору"),
}

/** Строка медиатеки: карточка + закреплена ли + имя автора для сортировки. */
data class LibraryEntry(val item: MediaItem, val pinned: Boolean, val creator: String, val order: Int)

data class LibraryUi(
    val entries: List<LibraryEntry> = emptyList(),
    val likedCount: Int = 0,
    val sort: LibrarySort = LibrarySort.Recent,
    val grid: Boolean = false,
    val recent: Map<String, Long> = emptyMap(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryState,
    private val prefs: UiPrefs,
    sessions: SessionStore,
) : ViewModel() {
    val user = sessions.user

    private val entries = combine(library.playlists, library.albums, library.artists, library.pins) { playlists, albums, artists, pins ->
        var order = 0
        buildList {
            playlists.forEach { p ->
                add(LibraryEntry(p.toMedia("Плейлист • ${p.owner?.name.orEmpty()}"), "playlist:${p.id}" in pins, p.owner?.name.orEmpty(), order++))
            }
            albums.forEach { r ->
                add(LibraryEntry(r.toMedia("${releaseKindLabel(r.type)} • ${r.artist?.name.orEmpty()}"), "album:${r.id}" in pins, r.artist?.name.orEmpty(), order++))
            }
            artists.forEach { a ->
                add(LibraryEntry(a.toMedia(), "artist:${a.id}" in pins, a.name, order++))
            }
        }
    }

    val ui: StateFlow<LibraryUi> = combine(entries, library.likedIds, prefs.librarySort, prefs.libraryGrid, prefs.recentContexts) { list, liked, sort, grid, recent ->
        LibraryUi(
            entries = list,
            likedCount = liked.size,
            sort = LibrarySort.entries.firstOrNull { it.key == sort } ?: LibrarySort.Recent,
            grid = grid,
            recent = recent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

    fun setSort(sort: LibrarySort) = prefs.setLibrarySort(sort.key)

    fun toggleGrid() = prefs.setLibraryGrid(!ui.value.grid)

    fun refresh() {
        viewModelScope.launch { library.refresh() }
    }

    fun create(title: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch { library.createPlaylist(title)?.let { onCreated(it.id) } }
    }
}

/** Моя медиатека (MobileLibraryPage.vue). */
@Composable
fun LibraryScreen(vm: LibraryViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle(null)
    val navigator = LocalNavigator.current
    var filter by rememberSaveable { mutableStateOf<LibraryFilter?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    val downloads = LocalDownloads.current
    val downloadedKeys = LibraryFilter.Downloaded.takeIf { filter == it }?.let {
        ui.entries.mapNotNull { e -> downloadKey(e.item)?.takeIf { k -> downloads.collection(k).active }?.let { e.item.key } }.toSet()
    }
    val items = remember(ui, filter, query, downloadedKeys) { arrange(ui, filter, query, downloadedKeys.orEmpty()) }
    val showLiked = (filter == null || filter == LibraryFilter.Playlists || (filter == LibraryFilter.Downloaded && downloads.collection("liked").active)) &&
        (query.isBlank() || LikedItem.title.contains(query.trim(), ignoreCase = true))
    val liked = LikedItem.copy(subtitle = "Плейлист • ${Format.trackCount(ui.likedCount.toLong())}")

    Column(Modifier.fillMaxSize().background(SukifyColors.Elevated)) {
        Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(user, size = 34.dp, onClick = LocalDrawer.current::open)
                Spacer(Modifier.width(12.dp))
                Text("Моя медиатека", style = SukifyType.PageTitle, color = SukifyColors.Text, modifier = Modifier.weight(1f))
                HeaderIcon(SukifyIcons.SearchSmall, "Поиск в медиатеке") {
                    searching = !searching
                    if (!searching) query = ""
                }
                HeaderIcon(SukifyIcons.Plus, "Создать плейлист") { creating = true }
            }
            AnimatedVisibility(searching) {
                SearchField(query, { query = it }, "Поиск в медиатеке", Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), autoFocus = true)
            }
            FilterChips(
                options = LibraryFilter.entries.map { it to it.label },
                selected = filter,
                onSelect = { filter = it },
                collapsible = true,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize().testTag("library_list"), contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp))) {
            item(key = "sortbar") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.bounceClick(pressed = 0.95f) { sortOpen = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(SukifyIcons.CaretDown, null, tint = SukifyColors.Text, modifier = Modifier.size(12.dp).rotate(180f))
                        Icon(SukifyIcons.CaretDown, null, tint = SukifyColors.Text, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(ui.sort.label, style = SukifyType.BodySmallBold, color = SukifyColors.Text)
                    }
                    Spacer(Modifier.weight(1f))
                    HeaderIcon(if (ui.grid) SukifyIcons.List else SukifyIcons.Grid, if (ui.grid) "Списком" else "Сеткой", onClick = vm::toggleGrid)
                }
            }
            val all = (if (showLiked) listOf(LibraryEntry(liked, pinned = true, creator = "", order = -1)) else emptyList()) + items
            if (ui.grid) {
                items(all.chunked(3), key = { row -> "g:" + row.first().item.key }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { entry -> GridTile(entry, Modifier.weight(1f)) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else {
                items(all, key = { it.item.key }) { entry ->
                    LibraryRow(entry, Modifier.animateItem())
                }
            }
            if (all.isEmpty()) {
                item {
                    Text(
                        if (query.isNotBlank()) "Ничего не нашлось" else "Здесь пока пусто — добавь плейлисты и альбомы.",
                        style = SukifyType.BodySmall,
                        color = SukifyColors.TextSubdued,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (sortOpen) {
        SortSheet(ui.sort, onPick = { vm.setSort(it); sortOpen = false }, onDismiss = { sortOpen = false })
    }
    if (creating) {
        NameDialog(
            title = "Новый плейлист",
            initial = "Мой плейлист №${ui.entries.count { it.item.kind == MediaKind.Playlist } + 1}",
            confirm = "Создать",
            onConfirm = { name ->
                creating = false
                vm.create(name) { id -> navigator.open(PlaylistRoute(id)) }
            },
            onDismiss = { creating = false },
        )
    }
}

/** Закреплённые — сверху, остальные — по выбранной сортировке. */
private fun arrange(ui: LibraryUi, filter: LibraryFilter?, query: String, downloaded: Set<String>): List<LibraryEntry> {
    val q = query.trim()
    val filtered = ui.entries.filter { e ->
        val kindOk = when (filter) {
            null -> true
            LibraryFilter.Playlists -> e.item.kind == MediaKind.Playlist
            LibraryFilter.Artists -> e.item.kind == MediaKind.Artist
            LibraryFilter.Albums -> e.item.kind == MediaKind.Release
            LibraryFilter.Downloaded -> e.item.key in downloaded
        }
        kindOk && (q.isEmpty() || e.item.title.contains(q, ignoreCase = true) || e.creator.contains(q, ignoreCase = true))
    }
    val sorted = when (ui.sort) {
        LibrarySort.Recent -> filtered.sortedWith(compareByDescending<LibraryEntry> { ui.recent[it.item.contextKey] ?: 0L }.thenBy { it.order })
        LibrarySort.Added -> filtered.sortedBy { it.order }
        LibrarySort.Alpha -> filtered.sortedBy { it.item.title.lowercase() }
        LibrarySort.Creator -> filtered.sortedWith(compareBy<LibraryEntry> { it.creator.lowercase() }.thenBy { it.item.title.lowercase() })
    }
    return sorted.filter { it.pinned } + sorted.filterNot { it.pinned }
}

/** Ключ подборки в загрузках (по id, как в манифесте). */
private fun downloadKey(item: MediaItem): String? = when (item.kind) {
    MediaKind.Release -> "release:${item.id}"
    MediaKind.Playlist -> "playlist:${item.id}"
    MediaKind.Artist -> "artist:${item.id}"
    else -> null
}

@Composable
private fun HeaderIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).bounceClick(pressed = 0.85f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = SukifyColors.Text, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun LibraryRow(entry: LibraryEntry, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.current
    val menus = LocalMenus.current
    val haptics = LocalHapticFeedback.current
    val playing = LocalPlayback.current.isContext(entry.item.contextKey)
    val item = entry.item
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { navigator.open(item.route()) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menus.entity(item)
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryCover(item, 64.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = SukifyType.Body, color = if (playing) SukifyColors.Accent else SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.pinned) {
                    Icon(SukifyIcons.Pin, "Закреплено", tint = SukifyColors.Accent, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(item.subtitle, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GridTile(entry: LibraryEntry, modifier: Modifier) {
    val navigator = LocalNavigator.current
    val menus = LocalMenus.current
    val haptics = LocalHapticFeedback.current
    val item = entry.item
    Column(
        modifier.combinedClickable(
            onClick = { navigator.open(item.route()) },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menus.entity(item)
            },
        ),
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (item.kind == MediaKind.Liked) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).background(LikedBrush, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(SukifyIcons.HeartFill, null, tint = SukifyColors.Text, modifier = Modifier.size(32.dp)) }
            } else {
                FillCover(
                    item.cover,
                    pxHint = 300,
                    modifier = Modifier.fillMaxWidth(),
                    shape = if (item.round) CircleShape else RoundedCornerShape(4.dp),
                    fallbackIcon = if (item.round) SukifyIcons.Person else SukifyIcons.Album,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, style = SukifyType.LabelBold, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle, style = SukifyType.Label, color = SukifyColors.TextSubdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


@Composable
private fun LibraryCover(item: MediaItem, size: androidx.compose.ui.unit.Dp) {
    if (item.kind == MediaKind.Liked) {
        Box(Modifier.size(size).background(LikedBrush, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Icon(SukifyIcons.HeartFill, null, tint = SukifyColors.Text, modifier = Modifier.size(size * 0.36f))
        }
    } else {
        CoverImage(
            item.cover,
            size = size,
            shape = if (item.round) CircleShape else RoundedCornerShape(4.dp),
            fallbackIcon = if (item.round) SukifyIcons.Person else SukifyIcons.Album,
        )
    }
}

@Composable
private fun SortSheet(current: LibrarySort, onPick: (LibrarySort) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = SukifyColors.CardPressed) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Сортировка", style = SukifyType.Title, color = SukifyColors.Text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            LibrarySort.entries.forEach { sort ->
                Row(
                    Modifier.fillMaxWidth().bounceClick(pressed = 0.98f) { onPick(sort) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(sort.label, style = SukifyType.Body, color = if (sort == current) SukifyColors.Accent else SukifyColors.Text, modifier = Modifier.weight(1f))
                    if (sort == current) Icon(SukifyIcons.Check, null, tint = SukifyColors.Accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

