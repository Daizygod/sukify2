package ru.nepalimsya.sukify.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.model.PlaylistDto
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.ui.components.TrackRow
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.menus.SearchField
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.HideChrome
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.shell.PlaylistAddRoute
import ru.nepalimsya.sukify.ui.shell.bottomChromePadding
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

data class AddUi(
    val query: String = "",
    val liked: ImmutableList<Track> = persistentListOf(),
    val found: ImmutableList<Track> = persistentListOf(),
    val inPlaylist: Set<Long> = emptySet(),
    val searching: Boolean = false,
) {
    /** Без запроса — любимые, которых в плейлисте ещё нет; с запросом — весь каталог. */
    val items: List<Track> get() = (if (query.isBlank()) liked else found).filter { it.id !in inPlaylist }
}

@OptIn(FlowPreview::class)
@HiltViewModel
class PlaylistAddViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val library: LibraryState,
    private val api: SukifyApi,
) : ViewModel() {
    val id = savedState.toRoute<PlaylistAddRoute>().id

    private val _ui = MutableStateFlow(AddUi())
    val ui: StateFlow<AddUi> = _ui.asStateFlow()
    private val query = MutableStateFlow("")
    private var playlist: PlaylistDto? = null

    init {
        viewModelScope.launch {
            val loaded = catalog.playlist(id).first { it is Loadable.Ready && !it.refreshing || it is Loadable.Failed }
            playlist = loaded.dataOrNull
            _ui.value = _ui.value.copy(inPlaylist = playlist?.tracks.orEmpty().map { it.id }.toSet())
        }
        viewModelScope.launch {
            catalog.likedTracks().collect { state ->
                state.dataOrNull?.let { list -> _ui.value = _ui.value.copy(liked = list.map { it.toDomain() }.toImmutableList()) }
            }
        }
        viewModelScope.launch {
            query.debounce(300).collectLatest { q ->
                if (q.isBlank()) {
                    _ui.value = _ui.value.copy(found = persistentListOf(), searching = false)
                    return@collectLatest
                }
                _ui.value = _ui.value.copy(searching = true)
                val result = apiCall { api.search(q.trim(), limit = 20).tracks }.getOrNull().orEmpty()
                _ui.value = _ui.value.copy(found = result.map { it.toDomain() }.toImmutableList(), searching = false)
            }
        }
    }

    fun setQuery(value: String) {
        _ui.value = _ui.value.copy(query = value)
        query.value = value
    }

    fun add(track: Track) {
        val target = playlist ?: return
        _ui.value = _ui.value.copy(inPlaylist = _ui.value.inPlaylist + track.id)
        viewModelScope.launch {
            if (!library.addToPlaylist(target, track)) {
                _ui.value = _ui.value.copy(inPlaylist = _ui.value.inPlaylist - track.id)
            }
        }
    }
}

/** «Добавить в этот плейлист» (AddToPlaylistPanel.vue). */
@Composable
fun PlaylistAddScreen(vm: PlaylistAddViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val playback = LocalPlayback.current
    val menus = LocalMenus.current
    val items = ui.items
    HideChrome()

    Column(Modifier.fillMaxSize().background(SukifyColors.Elevated).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).bounceClick(pressed = 0.88f) { navigator.back() }, contentAlignment = Alignment.Center) {
                Icon(SukifyIcons.Close, "Закрыть", tint = SukifyColors.Text, modifier = Modifier.size(18.dp))
            }
            Text("Добавить в этот плейлист", style = SukifyType.BodyBold, color = SukifyColors.Text, modifier = Modifier.padding(start = 8.dp))
        }
        SearchField(ui.query, vm::setQuery, "Поиск по Sukify", Modifier.padding(horizontal = 16.dp), autoFocus = false)
        Text(
            if (ui.query.isBlank()) "Из любимых треков" else "Результаты поиска",
            style = SukifyType.BodySmallBold,
            color = SukifyColors.TextSubdued,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp))) {
            items(items, key = { it.id }) { track ->
                TrackRow(
                    track = track,
                    playing = playback.playingTrackId == track.id,
                    onClick = { playback.contexts.playFrom(items, track, "Поиск", "") },
                    onMore = { menus.track(track) },
                    modifier = Modifier.animateItem(),
                    trailing = {
                        Box(
                            Modifier.size(40.dp).bounceClick(pressed = 0.85f, haptic = true) { vm.add(track) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(26.dp).background(SukifyColors.Chip, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(SukifyIcons.Plus, "Добавить", tint = SukifyColors.Text, modifier = Modifier.size(14.dp))
                            }
                        }
                    },
                )
            }
            if (items.isEmpty()) {
                item {
                    Text(
                        when {
                            ui.searching -> "Ищем…"
                            ui.query.isNotBlank() -> "Ничего не нашлось."
                            else -> "Все твои любимые треки уже в плейлисте — поищи что-нибудь ещё."
                        },
                        style = SukifyType.BodySmall,
                        color = SukifyColors.TextSubdued,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}
