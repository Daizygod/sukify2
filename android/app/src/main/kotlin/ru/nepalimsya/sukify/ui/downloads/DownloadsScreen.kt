package ru.nepalimsya.sukify.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.nepalimsya.sukify.data.offline.DownloadEntity
import ru.nepalimsya.sukify.data.offline.DownloadSettings
import ru.nepalimsya.sukify.data.offline.DownloadState
import ru.nepalimsya.sukify.data.offline.DownloadWorker
import ru.nepalimsya.sukify.data.offline.Downloads
import ru.nepalimsya.sukify.data.offline.DownloadsSummary
import ru.nepalimsya.sukify.data.offline.OfflineCatalogDao
import ru.nepalimsya.sukify.data.offline.OfflineState
import ru.nepalimsya.sukify.data.offline.OfflineStorage
import ru.nepalimsya.sukify.data.offline.OfflineSync
import ru.nepalimsya.sukify.data.offline.StorageVolume
import ru.nepalimsya.sukify.data.offline.SyncStatus
import ru.nepalimsya.sukify.ui.components.OutlineButton
import ru.nepalimsya.sukify.ui.components.PageScaffold
import ru.nepalimsya.sukify.ui.components.SettingSwitchRow
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.menus.ConfirmDialog
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.util.Format

/** Место на диске: сколько у Sukify, сколько свободно и всего. */
data class StorageInfo(val used: Long = 0, val free: Long = 0, val total: Long = 0)

/** Строка очереди с названием трека. */
data class QueueRow(val row: DownloadEntity, val title: String)

@HiltViewModel
class DownloadsScreenViewModel @Inject constructor(
    state: OfflineState,
    private val sync: OfflineSync,
    private val storage: OfflineStorage,
    private val dao: OfflineCatalogDao,
    val actions: Downloads,
) : ViewModel() {
    val settings: StateFlow<DownloadSettings> = state.settings
    val summary: StateFlow<DownloadsSummary> = state.summary
    val status: StateFlow<SyncStatus> = sync.status
    val sdCard: Boolean get() = storage.sdCardAvailable

    private val _storage = MutableStateFlow(StorageInfo())
    val storageInfo: StateFlow<StorageInfo> = _storage.asStateFlow()

    private val _estimates = MutableStateFlow<Map<String, Long>>(emptyMap())
    val estimates: StateFlow<Map<String, Long>> = _estimates.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueRow>>(emptyList())
    val queue: StateFlow<List<QueueRow>> = _queue.asStateFlow()

    init {
        viewModelScope.launch {
            // Размер на диске пересчитываем, пока экран открыт и идут загрузки.
            while (true) {
                val volume = settings.value.volume
                _storage.value = withContext(Dispatchers.IO) {
                    StorageInfo(storage.usedBytes(), storage.freeBytes(volume), storage.totalBytes(volume))
                }
                delay(2_000)
            }
        }
        viewModelScope.launch { _estimates.value = sync.estimates() }
        viewModelScope.launch {
            state.queue.collectLatest { rows ->
                val pending = rows.filter { it.state != DownloadState.DONE }.take(60)
                val titles = dao.tracksByIds(pending.map { it.trackId }).associate { it.id to it.title }
                _queue.value = pending.map { QueueRow(it, titles[it.trackId] ?: "Трек ${it.trackId}") }
            }
        }
    }
}

/** «Загрузки» — как раздел «Хранилище» и «Загрузки» в настройках Spotify. */
@Composable
fun DownloadsScreen(vm: DownloadsScreenViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val storage by vm.storageInfo.collectAsStateWithLifecycle()
    val estimates by vm.estimates.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    var confirmWipe by remember { mutableStateOf(false) }
    val actions = vm.actions
    val requestNotifications = rememberNotificationPermission()

    PageScaffold("Загрузки") {
        item(key = "storage") { StorageCard(storage) }
        item(key = "summary") { SummaryCard(summary, status, onRetry = actions::retryFailed, onSync = actions::syncNow) }

        item(key = "auto-head") { SectionTitle("Скачивать автоматически", "Новое в этих разделах медиатеки будет само появляться на телефоне") }
        item(key = "auto-liked") {
            SettingSwitchRow("Любимые треки", estimate(estimates["liked"]), settings.autoLiked) {
                if (it) requestNotifications()
                actions.setAuto(liked = it)
            }
        }
        item(key = "auto-playlists") {
            SettingSwitchRow("Плейлисты из медиатеки", estimate(estimates["playlists"], "свои и совместные"), settings.autoPlaylists) {
                if (it) requestNotifications()
                actions.setAuto(playlists = it)
            }
        }
        item(key = "auto-albums") {
            SettingSwitchRow("Альбомы из медиатеки", estimate(estimates["albums"]), settings.autoAlbums) {
                if (it) requestNotifications()
                actions.setAuto(albums = it)
            }
        }
        item(key = "auto-artists") {
            SettingSwitchRow("Исполнители из подписок", estimate(estimates["artists"], "вся дискография — может занять много места"), settings.autoArtists) {
                if (it) requestNotifications()
                actions.setAuto(artists = it)
            }
        }

        item(key = "opts-head") { SectionTitle("Как скачивать") }
        item(key = "cellular") {
            SettingSwitchRow("Через мобильную сеть", "Без этого загрузки ждут Wi-Fi", settings.cellular, onChange = actions::setCellular)
        }
        item(key = "previews") {
            SettingSwitchRow("30-секундные превью", "Треки, у которых в Sukify есть только отрывок (~0,5 МБ)", settings.previews, onChange = actions::setPreviews)
        }
        item(key = "offline") {
            SettingSwitchRow("Офлайн-режим", "Играет только скачанное, даже когда есть интернет", settings.offlineMode, onChange = actions::setOfflineMode)
        }
        if (vm.sdCard || settings.volume == StorageVolume.SdCard) {
            item(key = "volume") {
                SettingSwitchRow(
                    "Хранить на карте памяти",
                    if (settings.volume == StorageVolume.SdCard) "Скачанное лежит на SD-карте" else "Сейчас — в памяти телефона",
                    settings.volume == StorageVolume.SdCard,
                ) { actions.setVolume(if (it) StorageVolume.SdCard else StorageVolume.Phone) }
            }
        }

        if (queue.isNotEmpty()) {
            item(key = "queue-head") { SectionTitle("В очереди") }
            items(queue, key = { "q:${it.row.trackId}" }) { QueueItem(it) }
        }

        item(key = "wipe") {
            Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Удалить все загрузки",
                    style = SukifyType.BodyBold,
                    color = SukifyColors.Error,
                    modifier = Modifier.bounceClick(pressed = 0.95f) { confirmWipe = true }.padding(12.dp),
                )
            }
        }
    }

    if (confirmWipe) {
        ConfirmDialog(
            title = "Удалить все загрузки?",
            text = "С телефона пропадут все скачанные треки, а автоматическое скачивание выключится.",
            confirm = "Удалить",
            onConfirm = {
                confirmWipe = false
                actions.removeAll()
            },
            onDismiss = { confirmWipe = false },
        )
    }
}

private fun estimate(bytes: Long?, extra: String? = null): String =
    listOfNotNull(bytes?.takeIf { it > 0 }?.let { "≈ ${Format.bytes(it)}" }, extra).joinToString(" · ").ifEmpty { extra.orEmpty() }

@Composable
private fun StorageCard(info: StorageInfo) {
    val other = (info.total - info.free - info.used).coerceAtLeast(0)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SukifyColors.Card)
            .padding(16.dp),
    ) {
        Text("Хранилище", style = SukifyType.BodyBold, color = SukifyColors.Text)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(SukifyColors.CardPressed)) {
            if (info.total > 0) {
                val used = (info.used.toFloat() / info.total).coerceIn(0f, 1f)
                val others = (other.toFloat() / info.total).coerceIn(0f, 1f - used)
                if (used > 0f) Box(Modifier.weight(used.coerceAtLeast(0.005f)).fillMaxHeight().background(SukifyColors.Accent))
                if (others > 0f) Box(Modifier.weight(others).fillMaxHeight().background(SukifyColors.TextMuted))
                val rest = 1f - used.coerceAtLeast(0.005f) - others
                if (rest > 0f) Spacer(Modifier.weight(rest))
            }
        }
        Spacer(Modifier.height(12.dp))
        Legend(SukifyColors.Accent, "Загрузки Sukify", Format.bytes(info.used))
        Legend(SukifyColors.TextMuted, "Другие приложения", Format.bytes(other))
        Legend(SukifyColors.CardPressed, "Свободно", Format.bytes(info.free))
    }
}

@Composable
private fun Legend(color: Color, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, modifier = Modifier.weight(1f))
        Text(value, style = SukifyType.BodySmallBold, color = SukifyColors.Text)
    }
}

@Composable
private fun SummaryCard(summary: DownloadsSummary, status: SyncStatus, onRetry: () -> Unit, onSync: () -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val text = when {
            summary.total == 0 -> "Ничего не скачано. Нажми ⬇ на плейлисте, альбоме или в «Любимых»."
            summary.done == summary.total -> "Скачано ${Format.trackCount(summary.done.toLong())} · ${Format.bytes(summary.bytesTotal)}"
            else -> "Скачано ${summary.done} из ${Format.trackCount(summary.total.toLong())}"
        }
        Text(text, style = SukifyType.Body, color = SukifyColors.Text)
        if (summary.total > 0 && summary.done < summary.total) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (summary.bytesTotal > 0) summary.bytesDone.toFloat() / summary.bytesTotal else 0f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = SukifyColors.Accent,
                trackColor = SukifyColors.CardPressed,
                drawStopIndicator = {},
            )
        }
        if (summary.failed > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Не скачалось: ${summary.failed}",
                    style = SukifyType.BodySmall,
                    color = SukifyColors.Warning,
                    modifier = Modifier.weight(1f),
                )
                OutlineButton("Повторить", onClick = onRetry)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                when {
                    status.running -> "Сверяю с Sukify…"
                    status.lastSyncAt == 0L -> status.error ?: "Ещё не сверялось"
                    else -> "Сверено ${Format.relativeDate(status.lastSyncAt / 1000, now)}".replace("Сверено 1 минуту назад", "Сверено только что")
                },
                style = SukifyType.Caption,
                color = SukifyColors.TextSubdued,
                modifier = Modifier.weight(1f),
            )
            OutlineButton("Синхронизировать", onClick = onSync, enabled = !status.running)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp)) {
        Text(title, style = SukifyType.Title, color = SukifyColors.Text)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = SukifyType.Caption, color = SukifyColors.TextSubdued)
        }
    }
}

@Composable
private fun QueueItem(item: QueueRow) {
    val row = item.row
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, style = SukifyType.BodySmall, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                when {
                    row.state == DownloadState.FAILED && row.attempts >= DownloadWorker.MAX_ATTEMPTS -> "ошибка"
                    row.state == DownloadState.FAILED -> "повтор"
                    row.state == DownloadState.DOWNLOADING -> "${(row.bytes * 100 / row.size.coerceAtLeast(1))}%"
                    else -> "ждёт"
                },
                style = SukifyType.Caption,
                color = if (row.state == DownloadState.FAILED) SukifyColors.Warning else SukifyColors.TextSubdued,
            )
        }
        if (row.state == DownloadState.DOWNLOADING) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { row.bytes.toFloat() / row.size.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = SukifyColors.Accent,
                trackColor = SukifyColors.CardPressed,
                drawStopIndicator = {},
            )
        }
        row.error?.let { Text(it, style = SukifyType.Caption, color = SukifyColors.TextMuted) }
    }
}
