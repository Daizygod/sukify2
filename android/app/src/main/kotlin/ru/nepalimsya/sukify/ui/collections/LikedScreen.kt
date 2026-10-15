package ru.nepalimsya.sukify.ui.collections

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.datastore.SessionStore
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.model.TrackDto
import ru.nepalimsya.sukify.domain.ContextKeys
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.MediaKind
import ru.nepalimsya.sukify.domain.Track
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
import ru.nepalimsya.sukify.ui.components.ShuffleAction
import ru.nepalimsya.sukify.ui.components.TrackRow
import ru.nepalimsya.sukify.ui.downloads.DownloadButton
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.util.Format

/** Плитка «Любимые треки» для меню, медиатеки и главной. */
val LikedItem = MediaItem(
    kind = MediaKind.Liked,
    ref = "liked",
    title = "Любимые треки",
    subtitle = "Плейлист",
    cover = null,
    contextKey = ContextKeys.LIKED,
)

val LikedBrush: Brush get() = Brush.linearGradient(listOf(SukifyColors.LikedStart, SukifyColors.LikedEnd))

data class LikedUi(
    val state: Loadable<Unit> = Loadable.Loading,
    val tracks: ImmutableList<Track> = persistentListOf(),
)

@OptIn(FlowPreview::class)
@HiltViewModel
class LikedViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    library: LibraryState,
    sessions: SessionStore,
) : ViewModel() {
    private val data = ScreenData(viewModelScope, catalog.cachedLiked()) { catalog.likedTracks() }
    private val fresh = MutableStateFlow<List<TrackDto>?>(null)

    val owner = sessions.user

    /**
     * Снятый где угодно лайк убирает строку на месте — перечитывать тысячи
     * треков ради одной незачем; новый лайк — повод перечитать (как в вебе).
     */
    val ui: StateFlow<LikedUi> = combine(data.state, fresh, library.likedIds) { state, override, liked ->
        val list = override ?: state.dataOrNull.orEmpty()
        val visible = if (liked.isEmpty()) list else list.filter { it.id in liked }
        LikedUi(
            state = when (state) {
                is Loadable.Ready -> Loadable.Ready(Unit, state.refreshing, state.error)
                is Loadable.Failed -> if (override != null) Loadable.Ready(Unit) else state
                Loadable.Loading -> if (override != null) Loadable.Ready(Unit) else Loadable.Loading
            },
            tracks = visible.map { it.toDomain() }.toImmutableList(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LikedUi())

    init {
        viewModelScope.launch {
            library.likedIds.debounce(350).collect { liked ->
                val known = (fresh.value ?: data.state.value.dataOrNull)?.map { it.id }?.toSet() ?: return@collect
                if (liked.any { it !in known }) runCatching { fresh.value = catalog.refreshLiked() }
            }
        }
    }

    fun reload() = data.reload()
}

/** «Любимые треки» (LikedSongsPage.vue). */
@Composable
fun LikedScreen(vm: LikedViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val user by vm.owner.collectAsStateWithLifecycle(null)
    val tracks = ui.tracks
    val color = Color(0xFF5039AB)
    val key = ContextKeys.LIKED

    CollectionScaffold(
        color = color,
        title = "Любимые треки",
        stickyPlay = { ContextPlayButton(key, "Любимые треки", tracks, size = 40.dp) },
    ) { heroModifier ->
        item(key = "hero") {
            val menus = LocalMenus.current
            CollectionHero(
                color = color,
                title = "Любимые треки",
                modifier = heroModifier,
                cover = { HeroTile(LikedBrush, SukifyIcons.HeartFill) },
                meta = {
                    val owner = user?.name
                    MetaText(listOfNotNull(owner, "${Format.trackCount(tracks.size.toLong())}, ${Format.totalDuration(tracks.sumOf { it.durationMs }, approx = true)}").joinToString(" • "))
                },
                actions = {
                    DownloadButton(ContextKeys.LIKED, "Любимые треки")
                    IconAction(SukifyIcons.MoreBig, "Ещё", onClick = { menus.entity(LikedItem, tracks) })
                },
                play = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShuffleAction(key, "Любимые треки", tracks)
                        Spacer(Modifier.width(8.dp))
                        ContextPlayButton(key, "Любимые треки", tracks)
                    }
                },
            )
        }
        when (val state = ui.state) {
            is Loadable.Failed -> item { ErrorBlock(state.message, onRetry = vm::reload) }
            Loadable.Loading -> item { LoadingBlock() }
            is Loadable.Ready -> {
                if (tracks.isEmpty()) {
                    item { MetaText("Здесь появятся треки, которые ты отметишь ✓", Modifier.padding(16.dp)) }
                }
                items(tracks, key = { it.id }, contentType = { "track" }) { track ->
                    val playback = LocalPlayback.current
                    val menus = LocalMenus.current
                    TrackRow(
                        track = track,
                        playing = playback.playingTrackId == track.id,
                        onClick = { playback.contexts.playFrom(tracks, track, "Любимые треки", key) },
                        onMore = { menus.track(track) },
                        onSwipeQueue = { playback.contexts.enqueueTracks(listOf(track)) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}
