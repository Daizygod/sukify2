package ru.nepalimsya.sukify.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.datastore.SessionStore
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.MediaKind
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.domain.toMedia
import ru.nepalimsya.sukify.ui.common.ScreenData
import ru.nepalimsya.sukify.ui.components.BackButton
import ru.nepalimsya.sukify.ui.components.CardPair
import ru.nepalimsya.sukify.ui.components.ErrorBlock
import ru.nepalimsya.sukify.ui.components.FilterChips
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.PageScaffold
import ru.nepalimsya.sukify.ui.components.Shelf
import ru.nepalimsya.sukify.ui.components.ShelfHeader
import ru.nepalimsya.sukify.ui.shell.GenreRoute
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.shell.ReleaseRoute
import ru.nepalimsya.sukify.ui.shell.ReleasesRoute
import ru.nepalimsya.sukify.ui.shell.SectionRoute
import ru.nepalimsya.sukify.ui.shell.bottomChromePadding
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyTitleFamily
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.ui.theme.parseHexColor

// --- Секция главной ------------------------------------------------------------

@HiltViewModel
class SectionViewModel @Inject constructor(savedState: SavedStateHandle, catalog: CatalogRepository, sessions: SessionStore) : ViewModel() {
    val key = savedState.toRoute<SectionRoute>().key
    private val data = ScreenData(viewModelScope, catalog.peek("home")) { catalog.home() }
    val state = data.state
    val user = sessions.user

    fun reload() = data.reload()
}

/** «Показать все» с полки главной (SectionPage.vue). */
@Composable
fun SectionScreen(vm: SectionViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle(null)
    val navigator = LocalNavigator.current
    val menus = LocalMenus.current
    val playback = LocalPlayback.current
    val home = state.dataOrNull

    val title = when (vm.key) {
        "recent" -> "Ты недавно слушал(-а)"
        "new" -> "Новые релизы"
        else -> user?.name?.let { "Только для тебя, $it" } ?: "Популярно сейчас"
    }
    val trackList = when (vm.key) {
        "recent" -> home?.recentlyPlayed
        "new" -> null
        else -> home?.popularTracks
    }.orEmpty().map { it.toDomain() }.toImmutableList()
    val items: List<MediaItem> = if (vm.key == "new") {
        home?.newReleases.orEmpty().map { it.toMedia() }
    } else {
        trackList.map { t -> MediaItem(MediaKind.Release, t.release?.slug.orEmpty(), t.title, t.artistNames, t.cover, id = t.id) }
    }

    PageScaffold(title) {
        when {
            home == null && state is Loadable.Failed -> item { ErrorBlock((state as Loadable.Failed).message, onRetry = vm::reload) }
            home == null -> item { LoadingBlock() }
            items.isEmpty() -> item { Text("Здесь пока пусто.", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, modifier = Modifier.padding(16.dp)) }
            else -> items(items.chunked(2), key = { row -> row.first().key + row.first().id }) { row ->
                CardPair(
                    row[0],
                    row.getOrNull(1),
                    onOpen = { item ->
                        if (vm.key == "new") {
                            navigator.open(item)
                        } else {
                            val track = trackList.first { it.id == item.id }
                            if (track.release != null) navigator.open(ReleaseRoute(track.release.slug))
                            else playback.contexts.playFrom(trackList, track, title, "")
                        }
                    },
                    onLongPress = { item ->
                        if (vm.key == "new") menus.entity(item) else menus.track(trackList.first { it.id == item.id })
                    },
                )
            }
        }
    }
}

// --- Весь каталог --------------------------------------------------------------

data class ReleasesUi(
    val sort: String = "added",
    val items: ImmutableList<MediaItem> = persistentListOf(),
    val page: Int = 0,
    val lastPage: Int = 1,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val hasMore: Boolean get() = page == 0 || page < lastPage
}

@HiltViewModel
class ReleasesViewModel @Inject constructor(savedState: SavedStateHandle, private val api: SukifyApi) : ViewModel() {
    private val _ui = MutableStateFlow(ReleasesUi(sort = savedState.toRoute<ReleasesRoute>().sort))
    val ui: StateFlow<ReleasesUi> = _ui.asStateFlow()

    init {
        loadMore()
    }

    fun loadMore() {
        val s = _ui.value
        if (s.loading || !s.hasMore) return
        _ui.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            apiCall { api.releases(s.sort, s.page + 1) }
                .onSuccess { page ->
                    val c = _ui.value
                    _ui.value = c.copy(
                        items = (c.items + page.data.map { it.toMedia() }).distinctBy { it.key }.toImmutableList(),
                        page = page.meta?.currentPage ?: (c.page + 1),
                        lastPage = page.meta?.lastPage ?: (c.page + 1),
                        loading = false,
                    )
                }
                .onFailure { _ui.value = _ui.value.copy(loading = false, error = "Не получилось загрузить") }
        }
    }

    fun setSort(sort: String) {
        if (sort == _ui.value.sort) return
        _ui.value = ReleasesUi(sort = sort)
        loadMore()
    }
}

/** «Недавно в Sukify» → весь каталог с сортировкой (ReleasesPage.vue). */
@Composable
fun ReleasesScreen(vm: ReleasesViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val menus = LocalMenus.current
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, ui.items.size) { if (nearEnd) vm.loadMore() }

    PageScaffold("Все релизы", state = listState) {
        item(key = "sorts") {
            FilterChips(
                listOf("added" to "Недавно в Sukify", "date" to "Дата выхода", "name" to "По алфавиту"),
                ui.sort,
                { it?.let(vm::setSort) },
                Modifier.padding(vertical = 8.dp),
            )
        }
        items(ui.items.chunked(2), key = { row -> row.first().key }) { row ->
            CardPair(row[0], row.getOrNull(1), onOpen = navigator::open, onLongPress = { menus.entity(it) })
        }
        if (ui.loading) item(key = "loading") { LoadingBlock() }
        ui.error?.let { error -> item(key = "error") { ErrorBlock(error, onRetry = vm::loadMore) } }
    }
}

// --- Жанр ----------------------------------------------------------------------

@HiltViewModel
class GenreViewModel @Inject constructor(savedState: SavedStateHandle, catalog: CatalogRepository) : ViewModel() {
    val name = savedState.toRoute<GenreRoute>().name
    private val data = ScreenData(viewModelScope, catalog.peek("genre:$name")) { catalog.genre(name) }
    val state = data.state

    fun reload() = data.reload()
}

/** Жанр (GenrePage.vue): исполнители и релизы. */
@Composable
fun GenreScreen(vm: GenreViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val menus = LocalMenus.current
    val genre = state.dataOrNull
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val color = parseHexColor(listOf("#e13300", "#1e3264", "#8d67ab", "#e8115b", "#148a08", "#477d95")[(vm.name.hashCode() and 0x7fffffff) % 6]) ?: Color.DarkGray

    Box(Modifier.fillMaxSize().background(SukifyColors.Elevated)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp))) {
            item(key = "hero") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp + statusTop)
                        .background(Brush.verticalGradient(listOf(color, SukifyColors.Elevated))),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    Text(
                        vm.name,
                        style = SukifyType.Hero.copy(fontFamily = SukifyTitleFamily),
                        color = SukifyColors.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            when {
                genre == null && state is Loadable.Failed -> item { ErrorBlock((state as Loadable.Failed).message, onRetry = vm::reload) }
                genre == null -> item { LoadingBlock() }
                else -> {
                    if (genre.artists.isNotEmpty()) {
                        item(key = "artists") {
                            Shelf("Исполнители", genre.artists.map { it.toMedia() }, onOpen = navigator::open, onLongPress = { menus.entity(it) })
                        }
                    }
                    if (genre.releases.isNotEmpty()) {
                        item(key = "releases-head") { ShelfHeader("Релизы") }
                        items(genre.releases.map { it.toMedia() }.chunked(2), key = { row -> row.first().key }) { row ->
                            CardPair(row[0], row.getOrNull(1), onOpen = navigator::open, onLongPress = { menus.entity(it) })
                        }
                    }
                }
            }
        }
        Column(Modifier.padding(top = statusTop + 10.dp, start = 12.dp)) {
            BackButton(onClick = { navigator.back() })
        }
    }
}
