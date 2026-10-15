package ru.nepalimsya.sukify.ui.menus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.nepalimsya.sukify.data.model.PlaylistDto
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.MediaKind
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.SleepState
import ru.nepalimsya.sukify.playback.SleepTimer
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.downloads.LocalDownloads
import ru.nepalimsya.sukify.ui.icons.ExtraIcons
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.ArtistRoute
import ru.nepalimsya.sukify.ui.shell.MenuController
import ru.nepalimsya.sukify.ui.shell.MenuRequest
import ru.nepalimsya.sukify.ui.shell.Navigator
import ru.nepalimsya.sukify.ui.shell.ReleaseRoute
import ru.nepalimsya.sukify.ui.shell.TrackMenuOptions
import ru.nepalimsya.sukify.ui.shell.TransitionPickerRoute
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.util.Format

/**
 * Меню-шиты поверх приложения (ContextMenu.vue под палец): меню трека, меню
 * подборки и «Добавить в плейлист». [navigator] — стек текущей вкладки.
 */
@Composable
fun MenuHost(controller: MenuController, navigator: Navigator?, onNavigate: () -> Unit = {}) {
    val request = controller.request ?: return
    val vm: MenuViewModel = hiltViewModel()
    val open: (Any) -> Unit = { route ->
        controller.close()
        onNavigate()
        navigator?.open(route)
    }
    when (request) {
        is MenuRequest.TrackMenu -> TrackMenuSheet(request.track, request.options, vm, controller, open)
        is MenuRequest.Entity -> EntityMenuSheet(request, vm, controller)
        is MenuRequest.AddToPlaylist -> AddToPlaylistSheet(request.track, vm, controller)
        MenuRequest.SleepTimer -> SleepTimerSheet(vm, controller)
    }
}

@Composable
private fun MenuSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = SukifyColors.CardPressed,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).background(SukifyColors.TextMuted, CircleShape))
        },
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp), content = content)
    }
}

@Composable
private fun MenuHeader(cover: Cover?, title: String, subtitle: String, round: Boolean = false, tile: Brush? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (tile != null) {
            Box(Modifier.size(52.dp).background(tile, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                Icon(SukifyIcons.HeartFill, null, tint = SukifyColors.Text, modifier = Modifier.size(20.dp))
            }
        } else {
            CoverImage(cover, size = 52.dp, shape = if (round) CircleShape else RoundedCornerShape(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SukifyType.BodyBold, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 4.dp), color = SukifyColors.Divider)
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, tint: Color = SukifyColors.TextSubdued, iconRotation: Float = 0f, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .bounceClick(pressed = 0.98f, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp).rotate(iconRotation))
        Spacer(Modifier.width(16.dp))
        Text(text, style = SukifyType.Body, color = SukifyColors.Text)
    }
}

// --- Трек ---------------------------------------------------------------------

@Composable
private fun TrackMenuSheet(track: Track, options: TrackMenuOptions, vm: MenuViewModel, controller: MenuController, open: (Any) -> Unit) {
    val liked by vm.likedIds.collectAsStateWithLifecycle()
    val isLiked = track.id in liked
    val close = controller::close
    MenuSheet(onDismiss = close) {
        MenuHeader(track.cover, track.title, track.artistNames)
        MenuItem(
            if (isLiked) SukifyIcons.CheckCircle else SukifyIcons.PlusCircle,
            if (isLiked) "Удалить из любимых треков" else "Добавить в любимые треки",
            tint = if (isLiked) SukifyColors.Accent else SukifyColors.TextSubdued,
        ) { vm.toggleLike(track); close() }
        MenuItem(SukifyIcons.Plus, "Добавить в плейлист") { controller.addToPlaylist(track) }
        if (options.playlist != null && options.playlistItemId != null) {
            MenuItem(SukifyIcons.Close, "Удалить из этого плейлиста") {
                vm.removeFromPlaylist(options.playlist, options.playlistItemId)
                close()
            }
        }
        if (track.playable) {
            MenuItem(SukifyIcons.Queue, "Добавить в очередь") { vm.addToQueue(track); close() }
        }
        if (options.queueQid != null || options.upcomingIndex != null) {
            MenuItem(SukifyIcons.CaretDown, "Удалить из очереди", iconRotation = 180f) {
                vm.removeFromQueue(options.queueQid, options.upcomingIndex)
                close()
            }
        }
        val downloads = LocalDownloads.current
        if (track.playable) {
            when {
                downloads.isTrackIncluded(track.id) ->
                    MenuItem(ExtraIcons.DownloadArrow, "Удалить из загрузок", tint = SukifyColors.Accent) {
                        downloads.actions?.remove("track:${track.id}", track.title)
                        close()
                    }
                !downloads.isDownloaded(track.id) ->
                    MenuItem(SukifyIcons.DownloadCircle, "Скачать") {
                        downloads.actions?.add("track:${track.id}", track.title)
                        close()
                    }
            }
            MenuItem(SukifyIcons.Shuffle, "Создать переход…") { open(TransitionPickerRoute(track.id)) }
            MenuItem(SukifyIcons.Radio, "К радио по треку") { vm.startRadio(track); close() }
        }
        track.artists.firstOrNull()?.let { artist ->
            MenuItem(SukifyIcons.Person, if (track.artists.size > 1) "К исполнителю: ${artist.name}" else "К исполнителю") { open(ArtistRoute(artist.slug)) }
        }
        track.release?.let { release ->
            MenuItem(SukifyIcons.Album, "К альбому") { open(ReleaseRoute(release.slug)) }
        }
        MenuItem(SukifyIcons.Share, "Поделиться") { vm.shareTrack(track); close() }
        if (options.nowPlaying) {
            val sleep by vm.sleep.collectAsStateWithLifecycle()
            MenuItem(
                SukifyIcons.Moon,
                when (val s = sleep) {
                    SleepState.Off -> "Таймер сна"
                    SleepState.EndOfTrack -> "Таймер сна: до конца трека"
                    is SleepState.Until -> "Таймер сна: ${SleepTimer.minutesText(s.minutes)}"
                },
                tint = if (sleep != SleepState.Off) SukifyColors.Accent else SukifyColors.TextSubdued,
            ) { controller.sleepTimer() }
        }
    }
}

// --- Таймер сна -----------------------------------------------------------------

@Composable
private fun SleepTimerSheet(vm: MenuViewModel, controller: MenuController) {
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val close = controller::close
    MenuSheet(onDismiss = close) {
        Text("Таймер сна", style = SukifyType.Title, color = SukifyColors.Text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        SleepTimer.OPTIONS.forEach { minutes ->
            val on = (sleep as? SleepState.Until)?.minutes == minutes
            MenuItem(SukifyIcons.Moon, SleepTimer.minutesText(minutes), tint = if (on) SukifyColors.Accent else SukifyColors.TextSubdued) {
                vm.sleepFor(minutes)
                close()
            }
        }
        MenuItem(SukifyIcons.Moon, "До конца трека", tint = if (sleep == SleepState.EndOfTrack) SukifyColors.Accent else SukifyColors.TextSubdued) {
            vm.sleepEndOfTrack()
            close()
        }
        if (sleep != SleepState.Off) {
            MenuItem(SukifyIcons.Close, "Выключить таймер") {
                vm.sleepCancel()
                close()
            }
        }
    }
}

// --- Подборка -----------------------------------------------------------------

@Composable
private fun EntityMenuSheet(request: MenuRequest.Entity, vm: MenuViewModel, controller: MenuController) {
    val item = request.item
    val pins by vm.pins.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val close = controller::close
    val pinType = vm.pinType(item)
    val pinned = pinType != null && "${pinType.api}:${item.id}" in pins

    MenuSheet(onDismiss = close) {
        MenuHeader(
            cover = item.cover,
            title = item.title,
            subtitle = item.subtitle,
            round = item.round,
            tile = if (item.kind == MediaKind.Liked) Brush.linearGradient(listOf(SukifyColors.LikedStart, SukifyColors.LikedEnd)) else null,
        )
        if (item.contextKey != null) {
            MenuItem(SukifyIcons.Play, "Слушать") { vm.play(item, request.tracks); close() }
            MenuItem(SukifyIcons.Queue, "Добавить в очередь") { vm.enqueue(item, request.tracks); close() }
        }
        when (item.kind) {
            MediaKind.Release -> {
                val liked = albums.any { it.id == item.id }
                MenuItem(
                    if (liked) SukifyIcons.CheckCircle else SukifyIcons.PlusCircle,
                    if (liked) "Удалить из медиатеки" else "Добавить в медиатеку",
                    tint = if (liked) SukifyColors.Accent else SukifyColors.TextSubdued,
                ) { vm.toggleAlbum(item); close() }
            }
            MediaKind.Artist -> {
                val following = artists.any { it.id == item.id }
                MenuItem(
                    if (following) SukifyIcons.CheckCircle else SukifyIcons.PlusCircle,
                    if (following) "Отписаться" else "Подписаться",
                    tint = if (following) SukifyColors.Accent else SukifyColors.TextSubdued,
                ) { vm.toggleFollow(item); close() }
            }
            else -> Unit
        }
        if (pinType != null && (item.kind != MediaKind.Release || albums.any { it.id == item.id }) &&
            (item.kind != MediaKind.Artist || artists.any { it.id == item.id })
        ) {
            MenuItem(
                SukifyIcons.Pin,
                if (pinned) "Открепить" else "Закрепить в медиатеке",
                tint = if (pinned) SukifyColors.Accent else SukifyColors.TextSubdued,
            ) { vm.togglePin(item); close() }
        }
        downloadKey(item)?.let { key ->
            val downloads = LocalDownloads.current
            val active = downloads.collection(key).active
            MenuItem(
                if (active) ExtraIcons.DownloadArrow else SukifyIcons.DownloadCircle,
                if (active) "Удалить из загрузок" else "Скачать",
                tint = if (active) SukifyColors.Accent else SukifyColors.TextSubdued,
            ) {
                if (active) downloads.actions?.remove(key, item.title) else downloads.actions?.add(key, item.title)
                close()
            }
        }
        val playlist = request.playlist
        if (playlist?.isOwner == true) {
            MenuItem(SukifyIcons.Friends, "Пригласить соавторов") { vm.invite(playlist); close() }
            MenuItem(SukifyIcons.Close, "Удалить плейлист") { confirmDelete = true }
        }
        MenuItem(SukifyIcons.Share, "Поделиться") { vm.shareItem(item); close() }
    }

    if (confirmDelete && request.playlist != null) {
        ConfirmDialog(
            title = "Удалить плейлист?",
            text = "«${request.playlist.title}» пропадёт из медиатеки — и у соавторов тоже.",
            confirm = "Удалить",
            onConfirm = {
                confirmDelete = false
                vm.deletePlaylist(request.playlist) { controller.close() }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Ключ подборки для загрузок (по id, как в манифесте). */
private fun downloadKey(item: MediaItem): String? = when (item.kind) {
    MediaKind.Release -> "release:${item.id}"
    MediaKind.Playlist -> "playlist:${item.id}"
    MediaKind.Artist -> "artist:${item.id}".takeIf { item.id > 0 }
    MediaKind.Liked -> "liked"
    else -> null
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SukifyColors.CardPressed,
        title = { Text(title, style = SukifyType.Title, color = SukifyColors.Text) },
        text = { Text(text, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, style = SukifyType.BodyBold, color = SukifyColors.Error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", style = SukifyType.BodyBold, color = SukifyColors.Text) } },
    )
}

// --- Добавить в плейлист ------------------------------------------------------

@Composable
private fun AddToPlaylistSheet(track: Track, vm: MenuViewModel, controller: MenuController) {
    val all by vm.playlists.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val editable = all.filter { it.isOwner || it.isCollaborator }
    val shown = if (query.isBlank()) editable else editable.filter { it.title.contains(query.trim(), ignoreCase = true) }

    MenuSheet(onDismiss = controller::close) {
        Text(
            "Добавить в плейлист",
            style = SukifyType.Title,
            color = SukifyColors.Text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SearchField(query, onChange = { query = it }, placeholder = "Поиск плейлиста", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        MenuItem(SukifyIcons.Plus, "Создать плейлист") { creating = true }
        HorizontalDivider(color = SukifyColors.Divider)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(shown, key = { it.id }) { playlist ->
                PlaylistPickRow(playlist) {
                    vm.addToPlaylist(playlist, track)
                    controller.close()
                }
            }
        }
        if (shown.isEmpty()) {
            Text(
                if (editable.isEmpty()) "Своих плейлистов пока нет — создай первый." else "Ничего не нашлось",
                style = SukifyType.BodySmall,
                color = SukifyColors.TextSubdued,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    if (creating) {
        NameDialog(
            title = "Новый плейлист",
            initial = "Мой плейлист №${editable.size + 1}",
            confirm = "Создать",
            onConfirm = { name ->
                creating = false
                vm.createAndAdd(name, track)
                controller.close()
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun PlaylistPickRow(playlist: PlaylistDto, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .bounceClick(pressed = 0.98f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(Cover.single(playlist.coverUrl), size = 48.dp, shape = RoundedCornerShape(4.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, style = SukifyType.Body, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Format.trackCount((playlist.tracksCount ?: 0).toLong()),
                style = SukifyType.BodySmall,
                color = SukifyColors.TextSubdued,
            )
        }
    }
}

/** Поле поиска: светлое на тёмном (как поле поиска веба). */
@Composable
fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    light: Boolean = false,
    autoFocus: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(if (light) SukifyColors.Text else SukifyColors.Chip, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (light) Color.Black else SukifyColors.Text
        Icon(SukifyIcons.SearchSmall, null, tint = if (light) Color.Black else SukifyColors.TextSubdued, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, style = SukifyType.BodySmallBold, color = if (light) Color(0xFF535353) else SukifyColors.TextSubdued, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = SukifyType.BodySmallBold.copy(color = fg),
                cursorBrush = SolidColor(if (light) Color.Black else SukifyColors.Accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                SukifyIcons.Close,
                "Очистить",
                tint = fg,
                modifier = Modifier.size(28.dp).bounceClick(pressed = 0.85f) { onChange("") }.padding(6.dp),
            )
        }
    }
    if (autoFocus) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

/** Диалог с полем названия: новый плейлист. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SukifyColors.CardPressed,
        title = { Text(title, style = SukifyType.Title, color = SukifyColors.Text) },
        text = {
            BasicTextField(
                value = name,
                onValueChange = { name = it.take(255) },
                singleLine = true,
                textStyle = SukifyType.Title.copy(color = SukifyColors.Text),
                cursorBrush = SolidColor(SukifyColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SukifyColors.Chip, RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) {
                Text(confirm, style = SukifyType.BodyBold, color = SukifyColors.Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", style = SukifyType.BodyBold, color = SukifyColors.Text) } },
    )
}

