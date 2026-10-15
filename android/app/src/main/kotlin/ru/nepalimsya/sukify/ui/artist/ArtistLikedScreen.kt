package ru.nepalimsya.sukify.ui.artist

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.domain.ContextKeys
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.MediaKind
import ru.nepalimsya.sukify.domain.imageUrl
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.domain.toMedia
import ru.nepalimsya.sukify.ui.common.ScreenData
import ru.nepalimsya.sukify.ui.components.CollectionHero
import ru.nepalimsya.sukify.ui.components.CollectionScaffold
import ru.nepalimsya.sukify.ui.components.ContextPlayButton
import ru.nepalimsya.sukify.ui.components.ErrorBlock
import ru.nepalimsya.sukify.ui.components.HeroCover
import ru.nepalimsya.sukify.ui.components.IconAction
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.MetaText
import ru.nepalimsya.sukify.ui.components.Shelf
import ru.nepalimsya.sukify.ui.components.TrackRow
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.ArtistLikedRoute
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.parseHexColor
import ru.nepalimsya.sukify.util.Format

@HiltViewModel
class ArtistLikedViewModel @Inject constructor(savedState: SavedStateHandle, catalog: CatalogRepository) : ViewModel() {
    val slug = savedState.toRoute<ArtistLikedRoute>().slug
    private val artistData = ScreenData(viewModelScope, catalog.peek("artist:$slug")) { catalog.artist(slug) }
    private val likedData = ScreenData(viewModelScope, catalog.peek("artist-liked:$slug")) { catalog.artistLiked(slug) }
    val artist = artistData.state
    val liked = likedData.state

    fun reload() {
        artistData.reload()
        likedData.reload()
    }
}

/** «Любимые треки: <исполнитель>» (ArtistLikedPage.vue). */
@Composable
fun ArtistLikedScreen(vm: ArtistLikedViewModel = hiltViewModel()) {
    val artistState by vm.artist.collectAsStateWithLifecycle()
    val likedState by vm.liked.collectAsStateWithLifecycle()
    val artist = artistState.dataOrNull
    val liked = likedState.dataOrNull
    val tracks = remember(liked) { liked?.tracks.orEmpty().map { it.toDomain() }.toImmutableList() }
    val color = parseHexColor(artist?.colors?.background) ?: SukifyColors.CardPressed
    val name = artist?.name.orEmpty()
    val title = if (name.isEmpty()) "Любимые треки" else "Любимые треки: $name"
    val key = ContextKeys.artistLiked(vm.slug)

    CollectionScaffold(
        color = color,
        title = title,
        stickyPlay = { ContextPlayButton(key, title, tracks, size = 40.dp) },
    ) { heroModifier ->
        item(key = "hero") {
            val menus = LocalMenus.current
            CollectionHero(
                color = color,
                title = title,
                modifier = heroModifier,
                cover = { HeroCover(Cover.single(artist?.imageUrl()) ?: tracks.firstOrNull()?.cover, round = artist?.imageUrl() != null) },
                meta = { MetaText(Format.trackCount(tracks.size.toLong())) },
                actions = {
                    IconAction(SukifyIcons.MoreBig, "Ещё", onClick = {
                        menus.entity(MediaItem(MediaKind.Artist, vm.slug, title, name, Cover.single(artist?.imageUrl()), round = true, contextKey = key, id = artist?.id ?: 0), tracks)
                    })
                },
                play = { ContextPlayButton(key, title, tracks) },
            )
        }
        when (likedState) {
            is Loadable.Failed -> item { ErrorBlock((likedState as Loadable.Failed).message, onRetry = vm::reload) }
            Loadable.Loading -> item { LoadingBlock() }
            is Loadable.Ready -> {
                if (tracks.isEmpty()) {
                    item { MetaText("Здесь появятся любимые треки этого исполнителя.", Modifier.padding(16.dp)) }
                }
                items(tracks, key = { it.id }, contentType = { "track" }) { track ->
                    val playback = LocalPlayback.current
                    val menus = LocalMenus.current
                    TrackRow(
                        track = track,
                        playing = playback.playingTrackId == track.id,
                        onClick = { playback.contexts.playFrom(tracks, track, title, key) },
                        onMore = { menus.track(track) },
                        onSwipeQueue = { playback.contexts.enqueueTracks(listOf(track)) },
                    )
                }
                val releases = liked?.releases.orEmpty()
                if (releases.isNotEmpty()) {
                    item(key = "releases") {
                        val navigator = LocalNavigator.current
                        val menus = LocalMenus.current
                        Shelf(
                            title = "Понравившиеся релизы",
                            items = releases.map { it.toMedia() },
                            onOpen = navigator::open,
                            onLongPress = { menus.entity(it) },
                        )
                    }
                }
            }
        }
    }
}
