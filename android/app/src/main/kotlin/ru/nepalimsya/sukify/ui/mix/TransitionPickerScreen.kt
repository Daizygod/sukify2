package ru.nepalimsya.sukify.ui.mix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.playback.PlaybackEngine
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.menus.SearchField
import ru.nepalimsya.sukify.ui.shell.HideChrome
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.MixEditorRoute
import ru.nepalimsya.sukify.ui.shell.TransitionPickerRoute
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.util.Format

data class PickerUi(
    val from: Track? = null,
    val query: String = "",
    val results: ImmutableList<Track> = persistentListOf(),
    val searching: Boolean = false,
    /** Подсказки без запроса: что играет следом в очереди. */
    val suggestions: ImmutableList<Track> = persistentListOf(),
)

@OptIn(FlowPreview::class)
@HiltViewModel
class TransitionPickerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val api: SukifyApi,
    engine: PlaybackEngine,
) : ViewModel() {
    val fromId = savedState.toRoute<TransitionPickerRoute>().fromId
    private val query = MutableStateFlow("")
    private val _ui = MutableStateFlow(PickerUi())
    val ui: StateFlow<PickerUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            apiCall { api.tracksBulk(fromId.toString()).data.firstOrNull()?.toDomain() }.getOrNull()?.let { from ->
                _ui.value = _ui.value.copy(from = from)
            }
        }
        // Если трек сейчас играет — подсказываем то, что пойдёт за ним.
        val snapshot = engine.state.value.queue
        if (snapshot.current?.track?.id == fromId) {
            val next = (snapshot.manual.map { it.track } + snapshot.upcoming).take(5)
            _ui.value = _ui.value.copy(suggestions = next.filter { it.id != fromId }.toImmutableList())
        }
        viewModelScope.launch {
            query.debounce(250).collectLatest { q ->
                if (q.isBlank()) {
                    _ui.value = _ui.value.copy(results = persistentListOf(), searching = false)
                    return@collectLatest
                }
                _ui.value = _ui.value.copy(searching = true)
                val found = apiCall { api.search(q.trim(), limit = 12).tracks }.getOrNull().orEmpty()
                _ui.value = _ui.value.copy(
                    results = found.map { it.toDomain() }.filter { it.id != fromId }.toImmutableList(),
                    searching = false,
                )
            }
        }
    }

    fun setQuery(value: String) {
        _ui.value = _ui.value.copy(query = value)
        query.value = value
    }
}

/** «Создать переход…»: из какого трека — известно, выбираем, в какой (TransitionPicker.vue). */
@Composable
fun TransitionPickerScreen(vm: TransitionPickerViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val choose: (Track) -> Unit = { to -> navigator.replace(MixEditorRoute(vm.fromId, to.id)) }
    HideChrome()

    Column(Modifier.fillMaxSize().background(SukifyColors.Elevated).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).bounceClick(pressed = 0.88f) { navigator.back() }, contentAlignment = Alignment.Center) {
                Icon(SukifyIcons.Close, "Закрыть", tint = SukifyColors.Text, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.padding(start = 4.dp)) {
                Text("Создать переход", style = SukifyType.BodyBold, color = SukifyColors.Text)
                ui.from?.let {
                    Text("Из: ${it.title} — ${it.artistNames}", style = SukifyType.Caption, color = SukifyColors.TextSubdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        SearchField(ui.query, vm::setQuery, "В какой трек переходим?", Modifier.padding(horizontal = 16.dp), autoFocus = true)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (ui.query.isBlank()) {
                if (ui.suggestions.isNotEmpty()) {
                    item { Text("Дальше в очереди", style = SukifyType.BodySmallBold, color = SukifyColors.TextSubdued, modifier = Modifier.padding(16.dp)) }
                    items(ui.suggestions, key = { "s${it.id}" }) { PickRow(it) { choose(it) } }
                }
                item {
                    Text(
                        "Подсказка: переход применится, когда эти два трека сыграют подряд — в плейлисте, альбоме или очереди.",
                        style = SukifyType.Caption,
                        color = SukifyColors.TextSubdued,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(ui.results, key = { it.id }) { PickRow(it) { choose(it) } }
            if (ui.query.isNotBlank() && !ui.searching && ui.results.isEmpty()) {
                item { Text("Ничего не нашлось.", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun PickRow(track: Track, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().bounceClick(pressed = 0.98f, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(track.cover, size = 44.dp, shape = RoundedCornerShape(2.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = SukifyType.Body, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artistNames, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(Format.duration(track.durationMs), style = SukifyType.Caption, color = SukifyColors.TextSubdued)
    }
}
