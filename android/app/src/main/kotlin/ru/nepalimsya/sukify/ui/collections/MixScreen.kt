package ru.nepalimsya.sukify.ui.collections

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
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
import ru.nepalimsya.sukify.data.model.DailyMixDetailDto
import ru.nepalimsya.sukify.domain.ContextKeys
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.MediaKind
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.ui.common.ScreenData
import ru.nepalimsya.sukify.ui.components.CollectionHero
import ru.nepalimsya.sukify.ui.components.CollectionScaffold
import ru.nepalimsya.sukify.ui.components.ContextPlayButton
import ru.nepalimsya.sukify.ui.components.ErrorBlock
import ru.nepalimsya.sukify.ui.components.HeroTile
import ru.nepalimsya.sukify.ui.components.IconAction
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.MetaText
import ru.nepalimsya.sukify.ui.components.TrackRow
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.shell.MixRoute
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.parseHexColor
import ru.nepalimsya.sukify.util.Format

@HiltViewModel
class MixViewModel @Inject constructor(savedState: SavedStateHandle, catalog: CatalogRepository) : ViewModel() {
    val n = savedState.toRoute<MixRoute>().n
    private val data = ScreenData(viewModelScope, catalog.peek("mix:$n")) { catalog.dailyMix(n) }
    val state = data.state

    fun reload() = data.reload()
}

/** «Микс дня #N» (MixPage.vue). */
@Composable
fun MixScreen(vm: MixViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val mix: DailyMixDetailDto? = state.dataOrNull
    val color = parseHexColor(mix?.color) ?: SukifyColors.CardPressed
    val tracks = remember(mix) { mix?.tracks.orEmpty().map { it.toDomain() }.toImmutableList() }
    val key = ContextKeys.mix(vm.n)
    val title = mix?.title ?: "Микс дня #${vm.n}"

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
                cover = { HeroTile(SolidColor(color) as Brush, SukifyIcons.Nowplaying) },
                meta = {
                    MetaText(
                        listOfNotNull(
                            "Микс от Sukify",
                            mix?.genre,
                            mix?.let { "${Format.trackCount(tracks.size.toLong())}, ${Format.totalDuration(tracks.sumOf { it.durationMs }, approx = true)}" },
                        ).joinToString(" • "),
                    )
                },
                actions = {
                    IconAction(SukifyIcons.MoreBig, "Ещё", onClick = {
                        menus.entity(MediaItem(MediaKind.Mix, vm.n.toString(), title, mix?.genre.orEmpty(), null, color = mix?.color, contextKey = key, id = vm.n.toLong()), tracks)
                    })
                },
                play = { ContextPlayButton(key, title, tracks) },
            )
        }
        when (val s = state) {
            is Loadable.Failed -> item { ErrorBlock(s.message, onRetry = vm::reload) }
            Loadable.Loading -> item { LoadingBlock() }
            is Loadable.Ready -> items(tracks, key = { it.id }, contentType = { "track" }) { track ->
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
        }
        if (state is Loadable.Ready && tracks.isEmpty()) {
            item { MetaText("Микс пока пуст — послушай побольше, и он соберётся.", Modifier.padding(16.dp)) }
        }
    }
}
