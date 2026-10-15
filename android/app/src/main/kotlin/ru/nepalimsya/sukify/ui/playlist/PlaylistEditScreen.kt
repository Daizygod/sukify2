package ru.nepalimsya.sukify.ui.playlist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.model.PlaylistDto
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.domain.toDomain
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.Toasts
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.menus.ConfirmDialog
import ru.nepalimsya.sukify.ui.shell.HideChrome
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.PlaylistEditRoute
import ru.nepalimsya.sukify.ui.shell.bottomChromePadding
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Строка состава в редакторе: id записи плейлиста + трек. */
data class EditItem(val itemId: Long, val track: Track)

data class PlaylistDraft(
    val original: PlaylistDto,
    val title: String,
    val description: String,
    val isPublic: Boolean,
    val items: List<EditItem>,
    val removed: Set<Long> = emptySet(),
    val newCover: Uri? = null,
    val dropCover: Boolean = false,
) {
    private val originalItems: List<Long> get() = original.tracks.orEmpty().mapNotNull { it.playlistItemId }

    val detailsChanged: Boolean
        get() = title.trim() != original.title ||
            description.trim() != original.description.orEmpty() ||
            isPublic != original.isPublic

    val orderChanged: Boolean get() = items.map { it.itemId } != originalItems.filterNot { it in removed }

    val dirty: Boolean get() = detailsChanged || orderChanged || removed.isNotEmpty() || newCover != null || dropCover
}

@HiltViewModel
class PlaylistEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val library: LibraryState,
    private val api: SukifyApi,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val id = savedState.toRoute<PlaylistEditRoute>().id

    private val _draft = MutableStateFlow<PlaylistDraft?>(null)
    val draft: StateFlow<PlaylistDraft?> = _draft.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    init {
        viewModelScope.launch {
            val playlist = catalog.playlist(id).first { it !is Loadable.Loading && !(it is Loadable.Ready && it.refreshing) }
            val dto = playlist.dataOrNull ?: catalog.peek<PlaylistDto>("playlist:$id") ?: return@launch
            _draft.value = PlaylistDraft(
                original = dto,
                title = dto.title,
                description = dto.description.orEmpty(),
                isPublic = dto.isPublic,
                items = dto.tracks.orEmpty().mapNotNull { t -> t.playlistItemId?.let { EditItem(it, t.toDomain()) } },
            )
        }
    }

    fun update(transform: (PlaylistDraft) -> PlaylistDraft) {
        _draft.value = _draft.value?.let(transform)
    }

    fun remove(item: EditItem) = update { it.copy(items = it.items - item, removed = it.removed + item.itemId) }

    fun move(from: Int, to: Int) = update { d ->
        d.copy(items = d.items.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun save(onDone: () -> Unit) {
        val d = _draft.value ?: return
        if (d.title.isBlank()) {
            Toasts.show("Придумай название")
            return
        }
        if (!d.dirty) {
            onDone()
            return
        }
        _saving.value = true
        viewModelScope.launch {
            val ok = runSteps(d)
            _saving.value = false
            catalog.forget("playlist:$id")
            library.notifyPlaylistChanged(id)
            library.refreshPlaylists()
            if (ok) {
                Toasts.show("Изменения сохранены")
                onDone()
            }
        }
    }

    private suspend fun runSteps(d: PlaylistDraft): Boolean {
        if (d.detailsChanged) {
            val body = buildJsonObject {
                put("title", d.title.trim())
                put("description", d.description.trim().ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
                put("is_public", d.isPublic)
            }
            if (apiCall { api.updatePlaylist(id, body) }.isFailure) return fail("Не удалось сохранить название")
        }
        for (itemId in d.removed) {
            if (apiCall { api.removeFromPlaylist(id, itemId) }.isFailure) return fail("Не удалось удалить трек")
        }
        if (d.orderChanged && d.items.isNotEmpty()) {
            val body = buildJsonObject { putJsonArray("item_ids") { d.items.forEach { add(JsonPrimitive(it.itemId)) } } }
            if (apiCall { api.reorderPlaylist(id, body) }.isFailure) return fail("Не удалось сохранить порядок")
        }
        if (d.dropCover && d.newCover == null) {
            if (apiCall { api.deletePlaylistCover(id) }.isFailure) return fail("Не удалось убрать обложку")
        }
        d.newCover?.let { uri ->
            val part = withContext(Dispatchers.IO) { coverPart(uri) } ?: return fail("Не подошёл файл обложки")
            if (apiCall { api.uploadPlaylistCover(id, part) }.isFailure) return fail("Не подошёл файл обложки")
        }
        return true
    }

    private fun fail(message: String): Boolean {
        Toasts.show(message)
        return false
    }

    /** Фото из галереи → JPEG до 1600 px (лимит сервера — 8 МБ). */
    private fun coverPart(uri: Uri): MultipartBody.Part? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val side = maxOf(info.size.width, info.size.height)
            if (side > 1600) {
                val scale = 1600f / side
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        MultipartBody.Part.createFormData("cover", "cover.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
    }.getOrNull()
}

/** «Изменить плейлист» — как экран правки в приложении Spotify. */
@Composable
fun PlaylistEditScreen(vm: PlaylistEditViewModel = hiltViewModel()) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val haptics = LocalHapticFeedback.current
    var confirmLeave by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.update { it.copy(newCover = uri, dropCover = false) }
    }

    BackHandler(enabled = draft?.dirty == true) { confirmLeave = true }
    HideChrome()

    Column(Modifier.fillMaxSize().background(SukifyColors.Elevated).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).bounceClick(pressed = 0.88f) { if (draft?.dirty == true) confirmLeave = true else navigator.back() },
                contentAlignment = Alignment.Center,
            ) { Icon(SukifyIcons.Close, "Закрыть", tint = SukifyColors.Text, modifier = Modifier.size(18.dp)) }
            Text("Изменить плейлист", style = SukifyType.BodyBold, color = SukifyColors.Text, modifier = Modifier.weight(1f).padding(start = 8.dp))
            Text(
                if (saving) "Сохраняем…" else "Сохранить",
                style = SukifyType.BodyBold,
                color = if (saving) SukifyColors.TextSubdued else SukifyColors.Accent,
                modifier = Modifier
                    .bounceClick(enabled = !saving && draft != null, pressed = 0.94f) { vm.save { navigator.back() } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        val d = draft
        if (d == null) {
            LoadingBlock()
            return@Column
        }

        val listState = rememberLazyListState()
        // Первые 2 элемента списка — обложка и поля; треки идут после них.
        val headerCount = 2
        val reorder = rememberReorderableLazyListState(listState) { from, to ->
            val a = from.index - headerCount
            val b = to.index - headerCount
            if (a in d.items.indices && b in d.items.indices) {
                vm.move(a, b)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp))) {
            item(key = "cover") {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(180.dp)
                            .shadow(16.dp, RoundedCornerShape(4.dp))
                            .clip(RoundedCornerShape(4.dp))
                            .bounceClick(pressed = 0.97f) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            d.newCover != null -> AsyncImage(d.newCover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            d.dropCover -> CoverImage(null, size = 180.dp, shape = RoundedCornerShape(4.dp))
                            else -> CoverImage(Cover.single(d.original.coverUrl), size = 180.dp, shape = RoundedCornerShape(4.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(
                            "Сменить обложку",
                            style = SukifyType.BodySmallBold,
                            color = SukifyColors.Text,
                            modifier = Modifier.bounceClick(pressed = 0.94f) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        )
                        if (d.newCover != null || (!d.dropCover && d.original.coverUrl != null)) {
                            Text(
                                "Убрать",
                                style = SukifyType.BodySmallBold,
                                color = SukifyColors.TextSubdued,
                                modifier = Modifier.bounceClick(pressed = 0.94f) { vm.update { it.copy(newCover = null, dropCover = true) } },
                            )
                        }
                    }
                }
            }
            item(key = "fields") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    UnderlineField(d.title, { v -> vm.update { it.copy(title = v.take(255)) } }, "Название", SukifyType.Title)
                    Spacer(Modifier.height(16.dp))
                    UnderlineField(d.description, { v -> vm.update { it.copy(description = v.take(1000)) } }, "Добавь необязательное описание", SukifyType.BodySmall, singleLine = false)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Открытый плейлист", style = SukifyType.Body, color = SukifyColors.Text)
                            Text("Виден в профиле и в поиске", style = SukifyType.Caption, color = SukifyColors.TextSubdued)
                        }
                        Switch(
                            checked = d.isPublic,
                            onCheckedChange = { v -> vm.update { it.copy(isPublic = v) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = SukifyColors.Accent, checkedThumbColor = Color.White),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    if (d.items.isNotEmpty()) {
                        Text("Треки", style = SukifyType.BodyBold, color = SukifyColors.Text)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            items(d.items, key = { it.itemId }) { item ->
                ReorderableItem(reorder, key = item.itemId) { dragging ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer { shadowElevation = if (dragging) 16f else 0f }
                            .background(if (dragging) SukifyColors.CardPressed else SukifyColors.Elevated)
                            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(44.dp).bounceClick(pressed = 0.85f) {
                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                vm.remove(item)
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(22.dp).background(SukifyColors.TextSubdued, CircleShape), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(width = 10.dp, height = 2.dp).background(SukifyColors.Elevated))
                            }
                        }
                        CoverImage(item.track.cover, size = 44.dp, shape = RoundedCornerShape(2.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.track.title, style = SukifyType.Body, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.track.artistNames, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(
                            Modifier.size(48.dp).draggableHandle(
                                onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(SukifyIcons.List, "Перетащить", tint = SukifyColors.TextSubdued, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Отменить изменения?",
            text = "Правки этого плейлиста не сохранятся.",
            confirm = "Отменить",
            onConfirm = {
                confirmLeave = false
                navigator.back()
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

/** Поле с подчёркиванием — как в редакторе плейлиста Spotify. */
@Composable
private fun UnderlineField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    style: androidx.compose.ui.text.TextStyle,
    singleLine: Boolean = true,
) {
    Column {
        Box {
            if (value.isEmpty()) Text(placeholder, style = style, color = SukifyColors.TextMuted)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 5,
                textStyle = style.copy(color = SukifyColors.Text),
                cursorBrush = SolidColor(SukifyColors.Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(SukifyColors.TextMuted))
    }
}

