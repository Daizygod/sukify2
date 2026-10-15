package ru.nepalimsya.sukify.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.datastore.SessionStore
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.core.network.apiError
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.data.model.SearchDto
import ru.nepalimsya.sukify.data.offline.OfflineCatalog
import ru.nepalimsya.sukify.data.offline.OfflineState
import ru.nepalimsya.sukify.data.prefs.RecentSearch
import ru.nepalimsya.sukify.data.prefs.UiPrefs
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.domain.toMedia
import ru.nepalimsya.sukify.ui.common.ScreenData

/** Поиск: строка, чипы, результаты; без запроса — жанры и весь каталог. */
data class SearchResults(
    val query: String = "",
    val loading: Boolean = false,
    val data: SearchDto? = null,
    val error: String? = null,
)

data class CatalogPage(
    val sort: String = "added",
    val items: ImmutableList<MediaItem> = persistentListOf(),
    val page: Int = 0,
    val lastPage: Int = 1,
    val loading: Boolean = false,
) {
    val hasMore: Boolean get() = page < lastPage
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    catalog: CatalogRepository,
    private val api: SukifyApi,
    private val prefs: UiPrefs,
    sessions: SessionStore,
    private val offline: OfflineCatalog,
    private val offlineState: OfflineState,
) : ViewModel() {
    private val genresData = ScreenData(viewModelScope, catalog.peek("genres")) { catalog.genres() }
    val genres = genresData.state
    val recent: StateFlow<List<RecentSearch>> = prefs.recentSearches
    val user = sessions.user

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    private val _catalog = MutableStateFlow(CatalogPage())
    val catalogPage: StateFlow<CatalogPage> = _catalog.asStateFlow()
    private var catalogJob: Job? = null

    init {
        viewModelScope.launch {
            _query.debounce(300).collectLatest { q ->
                val trimmed = q.trim()
                if (trimmed.isEmpty()) {
                    _results.value = SearchResults()
                    return@collectLatest
                }
                _results.value = _results.value.copy(query = trimmed, loading = true)
                if (offlineState.onlyDownloaded.value) {
                    _results.value = SearchResults(trimmed, loading = false, data = offline.search(trimmed))
                    return@collectLatest
                }
                apiCall { api.search(trimmed) }
                    .onSuccess { _results.value = SearchResults(trimmed, loading = false, data = it) }
                    .onFailure { error ->
                        // Нет сети — ищем в скачанном и в копии медиатеки.
                        val local = offline.search(trimmed)
                        _results.value = if (local.tracks.isNotEmpty() || local.artists.isNotEmpty() || local.releases.isNotEmpty()) {
                            SearchResults(trimmed, loading = false, data = local)
                        } else {
                            SearchResults(trimmed, loading = false, error = error.apiError.message)
                        }
                    }
            }
        }
        loadMore()
    }

    fun setQuery(value: String) {
        _query.value = value
        if (value.isBlank()) _results.value = SearchResults()
        else _results.value = _results.value.copy(loading = true)
    }

    /** Весь каталог страницами по 48 (ReleaseGrid.vue). */
    fun loadMore() {
        val current = _catalog.value
        if (current.loading || !current.hasMore && current.page > 0) return
        _catalog.value = current.copy(loading = true)
        catalogJob = viewModelScope.launch {
            apiCall { api.releases(current.sort, current.page + 1) }
                .onSuccess { page ->
                    val c = _catalog.value
                    _catalog.value = c.copy(
                        items = (c.items + page.data.map { it.toMedia() }).distinctBy { it.key }.toImmutableList(),
                        page = page.meta?.currentPage ?: (c.page + 1),
                        lastPage = page.meta?.lastPage ?: c.page + 1,
                        loading = false,
                    )
                }
                .onFailure { _catalog.value = _catalog.value.copy(loading = false) }
        }
    }

    fun setSort(sort: String) {
        if (sort == _catalog.value.sort) return
        catalogJob?.cancel()
        _catalog.value = CatalogPage(sort = sort)
        loadMore()
    }

    fun retryGenres() = genresData.reload()

    fun remember(item: MediaItem) {
        prefs.addRecentSearch(
            RecentSearch(
                kind = item.kind.name,
                ref = item.ref,
                title = item.title,
                subtitle = item.subtitle,
                image = item.cover?.url(300),
                round = item.round,
            ),
        )
    }

    fun remember(track: Track, dto: ru.nepalimsya.sukify.data.model.TrackDto?) {
        prefs.addRecentSearch(
            RecentSearch(
                kind = "Track",
                ref = track.id.toString(),
                title = track.title,
                subtitle = "Трек • ${track.artistNames}",
                image = track.cover?.url(300),
                track = dto,
            ),
        )
    }

    fun forget(entry: RecentSearch) = prefs.removeRecentSearch(entry.key)

    fun clearRecent() = prefs.clearRecentSearches()
}
