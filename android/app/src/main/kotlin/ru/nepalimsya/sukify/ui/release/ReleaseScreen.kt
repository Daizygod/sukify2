package ru.nepalimsya.sukify.ui.release

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.data.model.ReleaseDto
import ru.nepalimsya.sukify.domain.ContextKeys
import ru.nepalimsya.sukify.domain.mainArtists
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.domain.imageUrl
import ru.nepalimsya.sukify.domain.releaseKindLabel
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.domain.toMedia
import ru.nepalimsya.sukify.ui.common.ScreenData
import ru.nepalimsya.sukify.ui.components.AvatarImage
import ru.nepalimsya.sukify.ui.components.CollectionHero
import ru.nepalimsya.sukify.ui.components.CollectionScaffold
import ru.nepalimsya.sukify.ui.components.ContextPlayButton
import ru.nepalimsya.sukify.ui.components.ErrorBlock
import ru.nepalimsya.sukify.ui.components.HeroCover
import ru.nepalimsya.sukify.ui.components.IconAction
import ru.nepalimsya.sukify.ui.components.LikeButton
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.MetaText
import ru.nepalimsya.sukify.ui.components.ShuffleAction
import ru.nepalimsya.sukify.ui.components.TrackRow
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.downloads.DownloadButton
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.ArtistRoute
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.shell.ReleaseRoute
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.ui.theme.parseHexColor
import ru.nepalimsya.sukify.util.Format

@HiltViewModel
class ReleaseViewModel @Inject constructor(
    savedState: SavedStateHandle,
    catalog: CatalogRepository,
    private val library: LibraryState,
) : ViewModel() {
    val slug = savedState.toRoute<ReleaseRoute>().slug

    private val data = ScreenData(viewModelScope, catalog.peek("release:$slug")) { catalog.release(slug) }
    val state: StateFlow<Loadable<ReleaseDto>> = data.state
    val albums = library.albums

    fun reload() = data.reload()

    fun toggleLike(release: ReleaseDto) {
        viewModelScope.launch { library.toggleAlbumLike(release) }
    }
}

/** Альбом/сингл (ReleasePage.vue). */
@Composable
fun ReleaseScreen(vm: ReleaseViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val release = state.dataOrNull
    val color = parseHexColor(release?.colors?.background) ?: SukifyColors.CardPressed

    CollectionScaffold(
        color = color,
        title = release?.title.orEmpty(),
        stickyPlay = release?.let { r ->
            { ContextPlayButton(ContextKeys.release(r.slug), r.title, null, size = 40.dp) }
        },
    ) { heroModifier ->
        when {
            release != null -> releaseContent(release, color, heroModifier, liked = albums.any { it.id == release.id }, onLike = { vm.toggleLike(release) })
            state is Loadable.Failed -> item { ErrorBlock((state as Loadable.Failed).message, onRetry = vm::reload, modifier = Modifier.padding(top = 120.dp)) }
            else -> item { LoadingBlock(Modifier.padding(top = 160.dp)) }
        }
    }
}

internal fun androidx.compose.foundation.lazy.LazyListScope.releaseContent(
    release: ReleaseDto,
    color: androidx.compose.ui.graphics.Color,
    heroModifier: Modifier,
    liked: Boolean,
    onLike: () -> Unit,
) {
    val key = ContextKeys.release(release.slug)
    val tracks = release.tracks.orEmpty().map { it.toDomain(release) }.toImmutableList()
    val totalMs = tracks.sumOf { it.durationMs }

    item(key = "hero", contentType = "hero") {
        val menus = LocalMenus.current
        CollectionHero(
            color = color,
            title = release.title,
            modifier = heroModifier,
            cover = { HeroCover(Cover.of(release.cover)) },
            meta = {
                ArtistLine(release.mainArtists())
                Spacer(Modifier.height(6.dp))
                MetaText(listOfNotNull(releaseKindLabel(release.type), release.year?.toString()).joinToString(" • "))
            },
            actions = {
                LikeButton(liked = liked, onToggle = onLike, size = 28.dp, tint = SukifyColors.TextSubdued)
                DownloadButton("release:${release.id}", release.title)
                IconAction(SukifyIcons.MoreBig, "Ещё", onClick = { menus.entity(release.toMedia(), tracks) })
            },
            play = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShuffleAction(key, release.title, tracks)
                    Spacer(Modifier.width(8.dp))
                    ContextPlayButton(key, release.title, tracks)
                }
            },
        )
    }

    itemsIndexed(tracks, key = { _, t -> t.id }, contentType = { _, _ -> "track" }) { _, track ->
        val playback = LocalPlayback.current
        val menus = LocalMenus.current
        TrackRow(
            track = track,
            playing = playback.playingTrackId == track.id,
            showCover = false,
            onClick = { playback.contexts.playFrom(tracks, track, release.title, key) },
            onMore = { menus.track(track) },
            onSwipeQueue = { playback.contexts.enqueueTracks(listOf(track)) },
        )
    }

    item(key = "footer", contentType = "footer") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp)) {
            if (!release.releaseDate.isNullOrEmpty()) {
                Text(Format.releaseDate(release.releaseDate), style = SukifyType.BodySmall, color = SukifyColors.Text)
                Spacer(Modifier.height(4.dp))
            }
            MetaText("${Format.trackCount(tracks.size.toLong())}, ${Format.totalDuration(totalMs)}")
        }
    }
}

/** Исполнители релиза: аватар + имя, каждый ведёт на свою страницу. */
@Composable
fun ArtistLine(artists: List<ArtistDto>) {
    val navigator = LocalNavigator.current
    FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        artists.forEach { artist ->
            Row(
                Modifier.bounceClick(pressed = 0.96f) { navigator.open(ArtistRoute(artist.slug)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val image = remember(artist.id) { artist.imageUrl() }
                if (image != null) {
                    AvatarImage(image, size = 24.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(artist.name, style = SukifyType.BodySmallBold, color = SukifyColors.Text)
            }
        }
    }
}
