package ru.nepalimsya.sukify.ui.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.data.model.ArtistLikedDto
import ru.nepalimsya.sukify.data.model.ReleaseDto
import ru.nepalimsya.sukify.domain.ContextKeys
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.domain.imageUrl
import ru.nepalimsya.sukify.domain.releaseKindLabel
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.domain.toMedia
import ru.nepalimsya.sukify.ui.common.ScreenData
import ru.nepalimsya.sukify.ui.components.AvatarImage
import ru.nepalimsya.sukify.ui.components.CollectionBarHeight
import ru.nepalimsya.sukify.ui.components.CollectionScaffold
import ru.nepalimsya.sukify.ui.components.ContextPlayButton
import ru.nepalimsya.sukify.ui.components.ErrorBlock
import ru.nepalimsya.sukify.ui.components.FilterChips
import ru.nepalimsya.sukify.ui.components.IconAction
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.MediaCard
import ru.nepalimsya.sukify.ui.components.MetaText
import ru.nepalimsya.sukify.ui.components.Shelf
import ru.nepalimsya.sukify.ui.components.ShelfHeader
import ru.nepalimsya.sukify.ui.components.ShuffleAction
import ru.nepalimsya.sukify.ui.components.TrackRow
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.components.shelfCardWidth
import ru.nepalimsya.sukify.ui.downloads.DownloadButton
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.ArtistLikedRoute
import ru.nepalimsya.sukify.ui.shell.ArtistRoute
import ru.nepalimsya.sukify.ui.shell.DiscographyRoute
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyTitleFamily
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.ui.theme.parseHexColor
import ru.nepalimsya.sukify.util.Format

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    catalog: CatalogRepository,
    private val library: LibraryState,
) : ViewModel() {
    val slug = savedState.toRoute<ArtistRoute>().slug
    private val artistData = ScreenData(viewModelScope, catalog.peek("artist:$slug")) { catalog.artist(slug) }
    private val topData = ScreenData(viewModelScope, catalog.peek("artist-top:$slug")) { catalog.artistTopTracks(slug) }
    private val likedData = ScreenData(viewModelScope, catalog.peek("artist-liked:$slug")) { catalog.artistLiked(slug) }
    val artist = artistData.state
    val top = topData.state
    val liked = likedData.state
    val followed = library.artists

    fun reload() {
        artistData.reload()
        topData.reload()
        likedData.reload()
    }

    fun toggleFollow(artist: ArtistDto) {
        viewModelScope.launch { library.toggleFollow(artist) }
    }
}

internal enum class DiscoTab(val label: String) { Popular("Популярные релизы"), Albums("Альбомы"), Singles("Синглы и EP") }

private fun ReleaseDto.isAlbum() = type == "album" || type == "compilation"

private fun ReleaseDto.isSingle() = type == "single" || type == "ep"

/** Исполнитель (ArtistPage.vue): шапка-баннер, популярное, дискография. */
@Composable
fun ArtistScreen(vm: ArtistViewModel = hiltViewModel()) {
    val artistState by vm.artist.collectAsStateWithLifecycle()
    val topState by vm.top.collectAsStateWithLifecycle()
    val likedState by vm.liked.collectAsStateWithLifecycle()
    val followedList by vm.followed.collectAsStateWithLifecycle()
    val artist = artistState.dataOrNull
    val topDtos = topState.dataOrNull.orEmpty()
    val top = remember(topDtos) { topDtos.map { it.toDomain() }.toImmutableList() }
    val color = parseHexColor(artist?.colors?.background) ?: SukifyColors.CardPressed
    val key = ContextKeys.artist(vm.slug)
    var expanded by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(DiscoTab.Popular) }

    CollectionScaffold(
        color = color,
        title = artist?.name.orEmpty(),
        stickyPlay = artist?.let { a -> { ContextPlayButton(key, a.name, top, size = 40.dp) } },
    ) { heroModifier ->
        when {
            artist != null -> artistContent(
                artist = artist,
                color = color,
                heroModifier = heroModifier,
                top = top,
                plays = topDtos.associate { it.id to (it.playsCount ?: 0L) },
                liked = likedState.dataOrNull,
                following = followedList.any { it.id == artist.id },
                expanded = expanded,
                onToggleExpanded = { expanded = !expanded },
                tab = tab,
                onTab = { tab = it },
                onFollow = { vm.toggleFollow(artist) },
            )
            artistState is Loadable.Failed -> item { ErrorBlock((artistState as Loadable.Failed).message, onRetry = vm::reload, modifier = Modifier.padding(top = 120.dp)) }
            else -> item { LoadingBlock(Modifier.padding(top = 160.dp)) }
        }
    }
}

internal fun LazyListScope.artistContent(
    artist: ArtistDto,
    color: Color,
    heroModifier: Modifier,
    top: List<Track>,
    plays: Map<Long, Long>,
    liked: ArtistLikedDto?,
    following: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    tab: DiscoTab,
    onTab: (DiscoTab) -> Unit,
    onFollow: () -> Unit,
) {
    val key = ContextKeys.artist(artist.slug)

    item(key = "hero", contentType = "hero") {
        ArtistHero(artist, color, heroModifier)
    }

    item(key = "actions", contentType = "actions") {
        val menus = LocalMenus.current
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            MetaText(Format.listeners(artist.monthlyListeners), Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (following) "Уже подписаны" else "Подписаться",
                    style = SukifyType.BodySmallBold,
                    color = SukifyColors.Text,
                    modifier = Modifier
                        .bounceClick(pressed = 0.94f, haptic = true, onClick = onFollow)
                        .border(1.dp, if (following) SukifyColors.Text else SukifyColors.TextMuted, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
                DownloadButton("artist:${artist.id}", artist.name, size = 24.dp)
                IconAction(SukifyIcons.MoreBig, "Ещё", onClick = { menus.entity(artist.toMedia(), top) })
                Spacer(Modifier.weight(1f))
                ShuffleAction(key, artist.name, top)
                Spacer(Modifier.width(8.dp))
                ContextPlayButton(key, artist.name, top)
            }
        }
    }

    if (top.isNotEmpty()) {
        item(key = "popular-head") { ShelfHeader("Популярные треки") }
        val shown = if (expanded) top else top.take(5)
        itemsIndexed(shown, key = { _, t -> "top:${t.id}" }, contentType = { _, _ -> "track" }) { i, track ->
            val playback = LocalPlayback.current
            val menus = LocalMenus.current
            TrackRow(
                track = track,
                playing = playback.playingTrackId == track.id,
                subtitle = Format.number(plays[track.id]),
                leading = {
                    Text(
                        "${i + 1}",
                        style = SukifyType.Body,
                        color = SukifyColors.TextSubdued,
                        modifier = Modifier.width(28.dp),
                    )
                },
                onClick = { playback.contexts.playFrom(top, track, artist.name, key) },
                onMore = { menus.track(track) },
                onSwipeQueue = { playback.contexts.enqueueTracks(listOf(track)) },
            )
        }
        if (top.size > 5) {
            item(key = "more") {
                Text(
                    if (expanded) "Свернуть" else "Ещё",
                    style = SukifyType.BodySmallBold,
                    color = SukifyColors.TextSubdued,
                    modifier = Modifier
                        .padding(start = 16.dp, top = 8.dp)
                        .bounceClick(pressed = 0.94f, onClick = onToggleExpanded)
                        .border(1.dp, SukifyColors.TextMuted, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }

    val likedTracks = liked?.tracks.orEmpty()
    if (likedTracks.isNotEmpty()) {
        item(key = "liked") { YouLikedCard(artist, likedTracks.size, likedTracks.map { it.toDomain() }) }
    }

    val releases = artist.releases.orEmpty()
    if (releases.isNotEmpty()) {
        item(key = "disco") {
            val navigator = LocalNavigator.current
            val menus = LocalMenus.current
            val tabs = buildList {
                add(DiscoTab.Popular to DiscoTab.Popular.label)
                if (releases.any { it.isAlbum() }) add(DiscoTab.Albums to DiscoTab.Albums.label)
                if (releases.any { it.isSingle() }) add(DiscoTab.Singles to DiscoTab.Singles.label)
            }
            val shown = when (tab) {
                DiscoTab.Albums -> releases.filter { it.isAlbum() }
                DiscoTab.Singles -> releases.filter { it.isSingle() }
                DiscoTab.Popular -> releases
            }
            val width = shelfCardWidth()
            Column {
                ShelfHeader("Дискография", onShowAll = { navigator.open(DiscographyRoute(artist.slug)) })
                if (tabs.size > 1) {
                    FilterChips(tabs, tab, { it?.let(onTab) }, Modifier.padding(bottom = 12.dp))
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(shown, key = { it.id }) { r ->
                        val item = r.toMedia(listOfNotNull(r.year?.toString(), releaseKindLabel(r.type)).joinToString(" • "))
                        MediaCard(item, width = width, onClick = { navigator.open(item) }, onLongClick = { menus.entity(item) })
                    }
                }
            }
        }
    }

    val appears = artist.appearsOn.orEmpty()
    if (appears.isNotEmpty()) {
        item(key = "appears") {
            val navigator = LocalNavigator.current
            val menus = LocalMenus.current
            Shelf(
                title = "С участием ${artist.name}",
                items = appears.map { it.toMedia() },
                onOpen = navigator::open,
                onLongPress = { menus.entity(it) },
            )
        }
    }

    if (!artist.bio.isNullOrBlank()) {
        item(key = "about") { AboutCard(artist) }
    }
}

/** Шапка исполнителя: баннер (или аватар) во всю ширину, имя поверх. */
@Composable
private fun ArtistHero(artist: ArtistDto, color: Color, modifier: Modifier) {
    val image = artist.bannerUrl ?: artist.avatarUrl
    // Подложка — цвет страницы, а цвет исполнителя живёт внутри градиента:
    // так на стыке шапки и списка не может проступить цветная строка пикселей.
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(if (image != null) 1.1f else 1.6f)
            .background(SukifyColors.Elevated),
    ) {
        if (image != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(image).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (image != null) {
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.25f),
                            0.35f to Color.Transparent,
                            0.7f to SukifyColors.Elevated.copy(alpha = 0.45f),
                            1f to SukifyColors.Elevated,
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to color,
                            0.6f to lerp(color, SukifyColors.Elevated, 0.55f),
                            1f to SukifyColors.Elevated,
                        )
                    },
                ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 8.dp, top = CollectionBarHeight)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SukifyIcons.Verified, null, tint = Color(0xFF4CB3FF), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Подтверждено Sukify", style = SukifyType.Caption, color = SukifyColors.Text)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                artist.name,
                style = SukifyType.Hero.copy(fontFamily = SukifyTitleFamily, fontSize = if (artist.name.length > 14) 36.sp else 48.sp),
                color = SukifyColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** «Вам нравится»: аватар с сердечком и счётчик — ведёт к любимым трекам исполнителя. */
@Composable
private fun YouLikedCard(artist: ArtistDto, count: Int, tracks: List<Track>) {
    val navigator = LocalNavigator.current
    val playback = LocalPlayback.current
    Column {
        ShelfHeader("Вам нравится")
        Row(
            Modifier
                .fillMaxWidth()
                .bounceClick(pressed = 0.98f) { navigator.open(ArtistLikedRoute(artist.slug)) }
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                AvatarImage(artist.imageUrl() ?: tracks.firstOrNull()?.cover?.url(200), size = 56.dp)
                Box(
                    Modifier.align(Alignment.BottomEnd).size(20.dp).background(SukifyColors.Accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(SukifyIcons.HeartFill, null, tint = Color.Black, modifier = Modifier.size(11.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("$count ${Format.plural(count.toLong(), "трек", "трека", "треков")}", style = SukifyType.BodyBold, color = SukifyColors.Text)
                Text("От: ${artist.name}", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
            }
            IconAction(SukifyIcons.Queue, "Добавить в очередь", size = 20.dp, onClick = { playback.contexts.enqueueTracks(tracks) })
        }
    }
}

/** «Об исполнителе»: фото, слушатели, биография. */
@Composable
private fun AboutCard(artist: ArtistDto) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp)) {
        Text("Об исполнителе", style = SukifyType.SectionTitle, color = SukifyColors.Text)
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SukifyColors.Card),
        ) {
            artist.imageUrl()?.let { url ->
                AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1.5f))
            }
            Column(Modifier.padding(16.dp)) {
                Text(Format.listeners(artist.monthlyListeners), style = SukifyType.BodyBold, color = SukifyColors.Text)
                Spacer(Modifier.height(8.dp))
                Text(artist.bio.orEmpty(), style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
