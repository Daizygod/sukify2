package ru.nepalimsya.sukify.ui.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.domain.MediaItem
import ru.nepalimsya.sukify.domain.releaseKindLabel
import ru.nepalimsya.sukify.domain.toMedia
import ru.nepalimsya.sukify.ui.common.ScreenData
import ru.nepalimsya.sukify.ui.components.BackButton
import ru.nepalimsya.sukify.ui.components.CardPair
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.ErrorBlock
import ru.nepalimsya.sukify.ui.components.FilterChips
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.DiscographyRoute
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.shell.bottomChromePadding
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

@HiltViewModel
class DiscographyViewModel @Inject constructor(savedState: SavedStateHandle, catalog: CatalogRepository) : ViewModel() {
    val slug = savedState.toRoute<DiscographyRoute>().slug
    private val data = ScreenData(viewModelScope, catalog.peek("artist:$slug")) { catalog.artist(slug) }
    val state = data.state

    fun reload() = data.reload()
}

private enum class DiscoFilter(val label: String) { All("Все"), Albums("Альбомы"), Singles("Синглы и EP") }

/** «Дискография» (DiscographyPage.vue): фильтр по типу, сортировка, сетка/список. */
@Composable
fun DiscographyScreen(vm: DiscographyViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val menus = LocalMenus.current
    var filter by rememberSaveable { mutableStateOf(DiscoFilter.All) }
    var byName by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    val artist = state.dataOrNull

    val all = artist?.releases.orEmpty()
    val filters = buildList {
        add(DiscoFilter.All to DiscoFilter.All.label)
        if (all.any { it.type == "album" || it.type == "compilation" }) add(DiscoFilter.Albums to DiscoFilter.Albums.label)
        if (all.any { it.type == "single" || it.type == "ep" }) add(DiscoFilter.Singles to DiscoFilter.Singles.label)
    }
    val releases = all
        .filter {
            when (filter) {
                DiscoFilter.Albums -> it.type == "album" || it.type == "compilation"
                DiscoFilter.Singles -> it.type == "single" || it.type == "ep"
                DiscoFilter.All -> true
            }
        }
        .let { list -> if (byName) list.sortedBy { it.title.lowercase() } else list.sortedByDescending { it.releaseDate.orEmpty() } }
        .map { it.toMedia(listOfNotNull(it.year?.toString(), releaseKindLabel(it.type)).joinToString(" • ")) }

    Column(Modifier.fillMaxSize().background(SukifyColors.Elevated).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onClick = { navigator.back() })
            Column(Modifier.padding(start = 12.dp)) {
                Text(artist?.name.orEmpty(), style = SukifyType.Caption, color = SukifyColors.TextSubdued, maxLines = 1)
                Text("Дискография", style = SukifyType.BodyBold, color = SukifyColors.Text)
            }
        }
        when {
            artist == null && state is Loadable.Failed -> ErrorBlock((state as Loadable.Failed).message, onRetry = vm::reload)
            artist == null -> LoadingBlock()
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp))) {
                item(key = "bar") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChips(filters, filter, { it?.let { f -> filter = f } }, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.bounceClick(pressed = 0.95f) { byName = !byName },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(SukifyIcons.CaretDown, null, tint = SukifyColors.Text, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (byName) "По алфавиту" else "Дата выпуска", style = SukifyType.BodySmallBold, color = SukifyColors.Text)
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (grid) SukifyIcons.List else SukifyIcons.Grid,
                            if (grid) "Показать списком" else "Показать сеткой",
                            tint = SukifyColors.TextSubdued,
                            modifier = Modifier.size(40.dp).bounceClick(pressed = 0.85f) { grid = !grid }.padding(10.dp),
                        )
                    }
                }
                if (grid) {
                    val rows = releases.chunked(2)
                    items(rows, key = { row -> row.first().key }) { row ->
                        CardPair(row[0], row.getOrNull(1), onOpen = navigator::open, onLongPress = { menus.entity(it) })
                    }
                } else {
                    items(releases, key = { it.key }) { item ->
                        DiscoRow(item, onOpen = { navigator.open(item) }, onLongPress = { menus.entity(item) })
                    }
                }
                if (releases.isEmpty()) {
                    item { Text("Здесь пока пусто.", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DiscoRow(item: MediaItem, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val playing = LocalPlayback.current.isContext(item.contextKey)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(item.cover, size = 80.dp, shape = RoundedCornerShape(4.dp))
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f)) {
            Column {
                Text(item.title, style = SukifyType.BodyBold, color = if (playing) SukifyColors.Accent else SukifyColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(item.subtitle, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, maxLines = 1)
            }
        }
    }
}
