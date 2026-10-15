package ru.nepalimsya.sukify.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.model.PlaylistDto
import ru.nepalimsya.sukify.data.model.TransitionSummaryDto
import ru.nepalimsya.sukify.ui.mix.MixChip
import ru.nepalimsya.sukify.domain.ContextKeys
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.domain.Track
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
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.MetaText
import ru.nepalimsya.sukify.ui.components.OutlineButton
import ru.nepalimsya.sukify.ui.components.ShuffleAction
import ru.nepalimsya.sukify.ui.components.Toasts
import ru.nepalimsya.sukify.ui.components.TrackRow
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.downloads.DownloadButton
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.LocalMenus
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.shell.MixEditorRoute
import ru.nepalimsya.sukify.ui.shell.PlaylistAddRoute
import ru.nepalimsya.sukify.ui.shell.PlaylistEditRoute
import ru.nepalimsya.sukify.ui.shell.PlaylistRoute
import ru.nepalimsya.sukify.ui.shell.ProfileRoute
import ru.nepalimsya.sukify.ui.shell.TrackMenuOptions
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.ui.theme.parseHexColor
import ru.nepalimsya.sukify.util.Format

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val library: LibraryState,
    private val api: SukifyApi,
) : ViewModel() {
    private val route = savedState.toRoute<PlaylistRoute>()
    val id = route.id

    private val data = ScreenData(viewModelScope, catalog.peek("playlist:$id")) { catalog.playlist(id) }
    val state: StateFlow<Loadable<PlaylistDto>> = data.state

    private val _recs = MutableStateFlow<ImmutableList<Track>>(persistentListOf())
    val recommendations: StateFlow<ImmutableList<Track>> = _recs.asStateFlow()

    /** Сводки переходов соседних пар — для чипов в режиме микса. */
    private val _summaries = MutableStateFlow<Map<String, TransitionSummaryDto?>>(emptyMap())
    val summaries: StateFlow<Map<String, TransitionSummaryDto?>> = _summaries.asStateFlow()

    fun loadSummaries(tracks: List<Track>) {
        val pairs = tracks.zipWithNext().map { (a, b) -> "${a.id}:${b.id}" }.distinct()
        if (pairs.isEmpty()) return
        viewModelScope.launch {
            val all = HashMap<String, TransitionSummaryDto?>()
            for (chunk in pairs.chunked(200)) {
                apiCall { api.transitionSummaries(chunk.joinToString(",")).data }.onSuccess { all.putAll(it) }
            }
            _summaries.value = all
        }
    }

    private val _mixBusy = MutableStateFlow(false)
    val mixBusy: StateFlow<Boolean> = _mixBusy.asStateFlow()

    init {
        route.join?.let { token -> viewModelScope.launch { join(token) } }
        loadRecommendations()
        // Трек добавили из меню, плейлист переименовали — перечитываем.
        viewModelScope.launch {
            library.playlistChanged.filter { it == id }.collect {
                data.reload()
                loadRecommendations()
            }
        }
    }

    fun reload() = data.reload()

    private fun loadRecommendations() {
        viewModelScope.launch {
            apiCall { api.playlistRecommendations(id).data }.onSuccess { list -> _recs.value = list.map { it.toDomain() }.toImmutableList() }
        }
    }

    /** Открыли ссылку-приглашение: /playlist/{id}?join={token}. */
    private suspend fun join(token: String) {
        apiCall { api.joinPlaylist(id, token) }
            .onSuccess {
                Toasts.show("Теперь ты участник этого плейлиста!")
                library.refreshPlaylists()
                data.reload()
            }
            .onFailure { Toasts.show("Ссылка-приглашение не подошла") }
    }

    fun addRecommendation(playlist: PlaylistDto, track: Track) {
        _recs.value = _recs.value.filterNot { it.id == track.id }.toImmutableList()
        viewModelScope.launch { library.addToPlaylist(playlist, track) }
    }

    /** «Создать микс»: темп и тональность у строк, переходы между ними. */
    fun toggleMix(playlist: PlaylistDto) {
        if (_mixBusy.value) return
        _mixBusy.value = true
        val next = !playlist.mixEnabled
        viewModelScope.launch {
            apiCall { api.setPlaylistMix(id, buildJsonObject { put("enabled", next) }).data }
                .onSuccess {
                    if (next) Toasts.show("Микс включён: видны BPM и тональность")
                    data.reload()
                }
                .onFailure { Toasts.show("Не удалось переключить микс") }
            _mixBusy.value = false
        }
    }
}

/** Плейлист (PlaylistPage.vue). */
@Composable
fun PlaylistScreen(vm: PlaylistViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val recs by vm.recommendations.collectAsStateWithLifecycle()
    val mixBusy by vm.mixBusy.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val playlist = state.dataOrNull
    val tracks = remember(playlist) { playlist?.tracks.orEmpty().map { it.toDomain() }.toImmutableList() }
    // Режим микса: сводки переходов для чипов; при каждом возврате (из редактора) — заново.
    val mixOn = playlist?.mixEnabled == true
    LifecycleResumeEffect(tracks, mixOn) {
        if (mixOn) vm.loadSummaries(tracks)
        onPauseOrDispose { }
    }
    // Шапка — в цвет обложки первого трека: из них и собран коллаж (как в вебе).
    val color = parseHexColor(playlist?.tracks?.firstOrNull()?.release?.colors?.background) ?: Color(0xFF535353)

    CollectionScaffold(
        color = color,
        title = playlist?.title.orEmpty(),
        stickyPlay = playlist?.let { p ->
            { ContextPlayButton(ContextKeys.playlist(p.id), p.title, tracks, size = 40.dp) }
        },
    ) { heroModifier ->
        when {
            playlist != null -> playlistContent(
                playlist = playlist,
                tracks = tracks,
                color = color,
                heroModifier = heroModifier,
                recommendations = recs,
                mixBusy = mixBusy,
                onToggleMix = { vm.toggleMix(playlist) },
                onAddRecommendation = { vm.addRecommendation(playlist, it) },
                summaries = summaries,
            )
            state is Loadable.Failed -> item { ErrorBlock((state as Loadable.Failed).message, onRetry = vm::reload, modifier = Modifier.padding(top = 120.dp)) }
            else -> item { LoadingBlock(Modifier.padding(top = 160.dp)) }
        }
    }
}

private fun LazyListScope.playlistContent(
    playlist: PlaylistDto,
    tracks: ImmutableList<Track>,
    color: Color,
    heroModifier: Modifier,
    recommendations: ImmutableList<Track>,
    mixBusy: Boolean,
    onToggleMix: () -> Unit,
    onAddRecommendation: (Track) -> Unit,
    summaries: Map<String, TransitionSummaryDto?>,
) {
    val key = ContextKeys.playlist(playlist.id)
    val canEdit = playlist.isOwner || playlist.isCollaborator
    val dtos = playlist.tracks.orEmpty()

    item(key = "hero", contentType = "hero") {
        val menus = LocalMenus.current
        val navigator = LocalNavigator.current
        CollectionHero(
            color = color,
            title = playlist.title,
            modifier = heroModifier,
            cover = { HeroCover(Cover.single(playlist.coverUrl)) },
            meta = {
                if (!playlist.description.isNullOrBlank()) {
                    MetaText(playlist.description)
                    Spacer(Modifier.height(8.dp))
                }
                playlist.owner?.let { owner ->
                    Row(
                        Modifier.bounceClick(pressed = 0.97f) { navigator.open(ProfileRoute(owner.username ?: owner.id.toString())) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarImage(owner.avatarUrl, size = 24.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(owner.name, style = SukifyType.BodySmallBold, color = SukifyColors.Text)
                        if (playlist.isCollaborative) {
                            Text(" и соавторы", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                val kind = when {
                    playlist.isCollaborative -> "Совместный плейлист"
                    playlist.isPublic -> "Открытый плейлист"
                    else -> "Закрытый плейлист"
                }
                MetaText("$kind • ${Format.trackCount(tracks.size.toLong())}, ${Format.totalDuration(tracks.sumOf { it.durationMs }, approx = true)}")
            },
            actions = {
                DownloadButton("playlist:${playlist.id}", playlist.title)
                IconAction(SukifyIcons.MoreBig, "Ещё", onClick = { menus.entity(playlist.toMedia(), tracks, playlist) })
            },
            play = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShuffleAction(key, playlist.title, tracks)
                    Spacer(Modifier.width(8.dp))
                    ContextPlayButton(key, playlist.title, tracks)
                }
            },
        )
    }

    if (canEdit) {
        item(key = "pills", contentType = "pills") {
            val navigator = LocalNavigator.current
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                item { Pill(SukifyIcons.Plus, "Добавить") { navigator.open(PlaylistAddRoute(playlist.id)) } }
                if (playlist.isOwner) {
                    item {
                        Pill(SukifyIcons.Sparkle, if (playlist.mixEnabled) "Микс включён" else "Создать микс", on = playlist.mixEnabled, enabled = !mixBusy, onClick = onToggleMix)
                    }
                    item { Pill(SukifyIcons.Edit, "Изменить") { navigator.open(PlaylistEditRoute(playlist.id)) } }
                }
            }
        }
    }

    if (tracks.isEmpty()) {
        item(key = "empty") {
            Text(
                "В плейлисте пока пусто — добавь треки через «Добавить» или меню трека.",
                style = SukifyType.BodySmall,
                color = SukifyColors.TextSubdued,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    itemsIndexed(tracks, key = { i, t -> dtos.getOrNull(i)?.playlistItemId ?: "t${t.id}:$i" }, contentType = { _, _ -> "track" }) { i, track ->
        val playback = LocalPlayback.current
        val menus = LocalMenus.current
        val navigator = LocalNavigator.current
        val dto = dtos.getOrNull(i)
        TrackRow(
            track = track,
            playing = playback.playingTrackId == track.id,
            subtitle = if (playlist.mixEnabled) mixSubtitle(track) else null,
            onClick = { playback.contexts.playFrom(tracks, track, playlist.title, key) },
            onMore = {
                menus.track(
                    track,
                    TrackMenuOptions(
                        playlist = playlist.takeIf { canEdit },
                        playlistItemId = dto?.playlistItemId?.takeIf { canEdit },
                    ),
                )
            },
            onSwipeQueue = { playback.contexts.enqueueTracks(listOf(track)) },
        )
        // Режим микса: чип перехода к следующему треку.
        if (playlist.mixEnabled && i < tracks.size - 1) {
            val next = tracks[i + 1]
            MixChip(summaries["${track.id}:${next.id}"]) { navigator.open(MixEditorRoute(track.id, next.id)) }
        }
    }

    if (playlist.isOwner && recommendations.isNotEmpty()) {
        item(key = "recs-head") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 8.dp)) {
                Text("Рекомендуем", style = SukifyType.SectionTitle, color = SukifyColors.Text)
                Spacer(Modifier.height(4.dp))
                MetaText("На основе того, что уже есть в этом плейлисте")
            }
        }
        items(recommendations, key = { "rec:${it.id}" }, contentType = { "rec" }) { track ->
            val playback = LocalPlayback.current
            val menus = LocalMenus.current
            TrackRow(
                track = track,
                playing = playback.playingTrackId == track.id,
                onClick = { playback.contexts.playFrom(recommendations, track, "Рекомендации", "") },
                onMore = { menus.track(track) },
                trailing = { OutlineButton("Добавить", onClick = { onAddRecommendation(track) }) },
            )
        }
    }
}

/** «128 BPM • 8A» — в режиме микса вместо исполнителей. */
private fun mixSubtitle(track: Track): String =
    listOfNotNull(track.bpm?.let { "$it BPM" }, track.camelot).joinToString(" • ").ifEmpty { track.artistNames }

/** Кнопка-таблетка в ряду правки плейлиста. */
@Composable
private fun Pill(icon: ImageVector, text: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .bounceClick(enabled = enabled, pressed = 0.95f, onClick = onClick)
            .background(if (on) SukifyColors.Accent else SukifyColors.Chip, CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (on) Color.Black else SukifyColors.Text, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = SukifyType.BodySmallBold, color = if (on) Color.Black else SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
