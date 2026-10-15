package ru.nepalimsya.sukify.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.data.model.ProfileDto
import ru.nepalimsya.sukify.data.prefs.UiPrefs
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.domain.toMedia
import ru.nepalimsya.sukify.ui.common.ScreenData
import ru.nepalimsya.sukify.ui.components.BackButton
import ru.nepalimsya.sukify.ui.components.CardPair
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.ErrorBlock
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.PageScaffold
import ru.nepalimsya.sukify.ui.components.ShelfHeader
import ru.nepalimsya.sukify.ui.components.Toasts
import ru.nepalimsya.sukify.ui.components.TrackRow
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.shell.ProfileRoute
import ru.nepalimsya.sukify.ui.shell.ReleaseRoute
import ru.nepalimsya.sukify.ui.shell.UserAvatar
import ru.nepalimsya.sukify.ui.shell.bottomChromePadding
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyTitleFamily
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.util.Format

// --- История прослушивания ---------------------------------------------------------

@HiltViewModel
class HistoryViewModel @Inject constructor(catalog: CatalogRepository) : ViewModel() {
    private val data = ScreenData(viewModelScope, catalog.peek("history")) { catalog.history() }
    val state = data.state

    fun reload() = data.reload()
}

/** История прослушивания (HistoryPage.vue): по дням, со временем. */
@Composable
fun HistoryScreen(vm: HistoryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val items = state.dataOrNull
    val playback = LocalPlayback.current
    val menus = LocalMenus.current
    val tracks = remember(items) { items.orEmpty().map { it.track.toDomain() }.toImmutableList() }
    val groups = remember(items) {
        items.orEmpty().mapIndexed { i, item -> i to item }
            .groupBy { (_, item) -> Format.epochSeconds(item.playedAt)?.let(Format::day).orEmpty() }
            .toList()
    }

    PageScaffold("История прослушивания") {
        when {
            items == null && state is Loadable.Failed -> item { ErrorBlock((state as Loadable.Failed).message, onRetry = vm::reload) }
            items == null -> item { LoadingBlock() }
            items.isEmpty() -> item {
                Text(
                    "Пока пусто — включи что-нибудь, и история появится здесь.",
                    style = SukifyType.BodySmall,
                    color = SukifyColors.TextSubdued,
                    modifier = Modifier.padding(16.dp),
                )
            }
            else -> groups.forEach { (day, list) ->
                item(key = "day:$day") {
                    Text(day, style = SukifyType.BodyBold, color = SukifyColors.Text, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
                }
                items(list, key = { (i, _) -> "h:$i" }) { (i, item) ->
                    val track = tracks[i]
                    TrackRow(
                        track = track,
                        playing = playback.playingTrackId == track.id,
                        onClick = { playback.contexts.playFrom(tracks, track, "История", "") },
                        onMore = { menus.track(track) },
                        onSwipeQueue = { playback.contexts.enqueueTracks(listOf(track)) },
                        trailing = {
                            Text(
                                Format.epochSeconds(item.playedAt)?.let(Format::time).orEmpty(),
                                style = SukifyType.Caption,
                                color = SukifyColors.TextSubdued,
                            )
                        },
                    )
                }
            }
        }
    }
}

// --- Что нового ----------------------------------------------------------------

@HiltViewModel
class NotificationsViewModel @Inject constructor(catalog: CatalogRepository, private val prefs: UiPrefs) : ViewModel() {
    private val data = ScreenData(viewModelScope, catalog.peek("notifications")) { catalog.notifications() }
    val state = data.state

    init {
        // Открыли «Что нового» — точка на аватаре гаснет до следующего релиза.
        viewModelScope.launch {
            data.state.collectLatest { s -> s.dataOrNull?.firstOrNull()?.releaseDate?.let(prefs::markBellSeen) }
        }
    }

    fun reload() = data.reload()
}

/** «Что нового»: свежие релизы исполнителей из подписок (колокольчик веба). */
@Composable
fun NotificationsScreen(vm: NotificationsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val list = state.dataOrNull
    PageScaffold("Что нового") {
        when {
            list == null && state is Loadable.Failed -> item { ErrorBlock((state as Loadable.Failed).message, onRetry = vm::reload) }
            list == null -> item { LoadingBlock() }
            list.isEmpty() -> item {
                Text(
                    "Пока нет новых уведомлений. Здесь появятся новые релизы исполнителей, на которых ты подписан(-а).",
                    style = SukifyType.BodySmall,
                    color = SukifyColors.TextSubdued,
                    modifier = Modifier.padding(16.dp),
                )
            }
            else -> items(list, key = { it.id }) { release ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .bounceClick(pressed = 0.98f) { navigator.open(ReleaseRoute(release.slug)) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverImage(Cover.of(release.cover), size = 56.dp, shape = RoundedCornerShape(4.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(release.title, style = SukifyType.Body, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Новый релиз • ${release.artist?.name.orEmpty()}",
                            style = SukifyType.BodySmall,
                            color = SukifyColors.TextSubdued,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!release.releaseDate.isNullOrEmpty()) {
                            Text(Format.releaseDate(release.releaseDate), style = SukifyType.Caption, color = SukifyColors.TextMuted)
                        }
                    }
                }
            }
        }
    }
}

// --- Профиль -------------------------------------------------------------------

@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val api: SukifyApi,
) : ViewModel() {
    val username = savedState.toRoute<ProfileRoute>().username
    private val data = ScreenData(viewModelScope, catalog.peek("user:$username")) { catalog.profile(username) }

    /** Подписка меняется на экране сразу, запрос — следом (как в вебе). */
    private val _override = MutableStateFlow<ProfileDto?>(null)
    val override: StateFlow<ProfileDto?> = _override.asStateFlow()
    val state = data.state

    fun reload() = data.reload()

    fun toggleFollow(profile: ProfileDto) {
        val follow = !profile.isFollowed
        val next = if (follow) {
            profile.copy(isFollowed = true, isFriend = profile.followsMe, followersCount = profile.followersCount + 1)
        } else {
            profile.copy(isFollowed = false, isFriend = false, followersCount = (profile.followersCount - 1).coerceAtLeast(0))
        }
        _override.value = next
        viewModelScope.launch {
            val name = profile.user.username ?: profile.user.id.toString()
            apiCall { if (follow) api.followUser(name) else api.unfollowUser(name) }
                .onSuccess {
                    if (follow) {
                        Toasts.show(
                            if (next.isFriend) "Теперь вы друзья с ${profile.user.name} — активность видна в «Друзьях»"
                            else "Ты подписан(-а) на ${profile.user.name}",
                        )
                    }
                }
                .onFailure {
                    _override.value = profile
                    Toasts.show("Не получилось — нет связи с сервером")
                }
        }
    }
}

/** Профиль (ProfilePage.vue): аватар, счётчики, подписка, общие друзья, плейлисты. */
@Composable
fun ProfileScreen(vm: ProfileViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val override by vm.override.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val menus = LocalMenus.current
    val profile = override ?: state.dataOrNull
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(Modifier.fillMaxSize().background(SukifyColors.Elevated)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp))) {
            when {
                profile == null && state is Loadable.Failed -> item { ErrorBlock((state as Loadable.Failed).message, onRetry = vm::reload, modifier = Modifier.padding(top = 120.dp)) }
                profile == null -> item { LoadingBlock(Modifier.padding(top = 160.dp)) }
                else -> {
                    item(key = "hero") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(SukifyColors.NowPlayingFallback, SukifyColors.Elevated)))
                                .padding(top = statusTop + 64.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            UserAvatar(profile.user, size = 132.dp)
                            Spacer(Modifier.height(16.dp))
                            Text(profile.user.name, style = SukifyType.Hero.copy(fontFamily = SukifyTitleFamily), color = SukifyColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                listOfNotNull(
                                    profile.user.username?.let { "@$it" },
                                    "${profile.followersCount} ${Format.plural(profile.followersCount.toLong(), "подписчик", "подписчика", "подписчиков")}",
                                    "${profile.friendsCount} ${Format.plural(profile.friendsCount.toLong(), "друг", "друга", "друзей")}",
                                ).joinToString(" • "),
                                style = SukifyType.BodySmall,
                                color = SukifyColors.TextSubdued,
                            )
                            if (!profile.isMe) {
                                Spacer(Modifier.height(16.dp))
                                val label = when {
                                    profile.isFriend -> "Вы друзья"
                                    profile.isFollowed -> "Вы подписаны"
                                    profile.followsMe -> "Подписаться в ответ"
                                    else -> "Подписаться"
                                }
                                Text(
                                    label,
                                    style = SukifyType.BodySmallBold,
                                    color = SukifyColors.Text,
                                    modifier = Modifier
                                        .bounceClick(pressed = 0.94f, haptic = true) { vm.toggleFollow(profile) }
                                        .border(1.dp, if (profile.isFriend) SukifyColors.Accent else SukifyColors.TextMuted, CircleShape)
                                        .padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                                relationLabel(profile)?.let { text ->
                                    Spacer(Modifier.height(10.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (profile.isFriend) {
                                            Icon(SukifyIcons.Friends, null, tint = SukifyColors.Accent, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(text, style = SukifyType.Caption, color = SukifyColors.TextSubdued)
                                    }
                                }
                            }
                        }
                    }
                    if (profile.mutualFriends.isNotEmpty()) {
                        item(key = "mutual") {
                            Column {
                                ShelfHeader("Общие друзья")
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    items(profile.mutualFriends, key = { it.id }) { friend ->
                                        Column(
                                            Modifier.width(84.dp).bounceClick(pressed = 0.95f) { navigator.open(ProfileRoute(friend.username ?: friend.id.toString())) },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            UserAvatar(friend, size = 72.dp)
                                            Spacer(Modifier.height(6.dp))
                                            Text(friend.name, style = SukifyType.Caption, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "pl-head") { ShelfHeader("Открытые плейлисты") }
                    if (profile.playlists.isEmpty()) {
                        item { Text("Пока нет открытых плейлистов.", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, modifier = Modifier.padding(horizontal = 16.dp)) }
                    }
                    items(profile.playlists.map { it.toMedia("Автор: ${profile.user.name}") }.chunked(2), key = { row -> row.first().key }) { row ->
                        CardPair(row[0], row.getOrNull(1), onOpen = navigator::open, onLongPress = { menus.entity(it) })
                    }
                }
            }
        }
        Box(Modifier.padding(top = statusTop + 10.dp, start = 12.dp)) {
            BackButton(onClick = { navigator.back() })
        }
    }
}

/** Одной фразой: кто вы друг другу. */
private fun relationLabel(p: ProfileDto): String? = when {
    p.isMe -> null
    p.isFriend -> "Вы друзья — подписаны друг на друга"
    p.followsMe -> "Подписан(-а) на вас — подпишись в ответ, и станете друзьями"
    p.isFollowed -> "Вы подписаны — станете друзьями, когда подпишутся в ответ"
    else -> null
}

