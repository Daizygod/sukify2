package ru.nepalimsya.sukify.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.playback.PlayerState
import ru.nepalimsya.sukify.playback.queue.QueuedTrack
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Очередь (MobileQueueSheet.vue): «Сейчас играет», «Дальше в очереди»
 * (ручная очередь) и «Далее из: …». Тап — играть, смахнуть — убрать,
 * потянуть за ручку — переставить.
 */
@Composable
fun QueueSheet(
    state: PlayerState,
    onDismiss: () -> Unit,
    onPlayManual: (Long) -> Unit,
    onPlayUpcoming: (Int) -> Unit,
    onRemoveManual: (Long) -> Unit,
    onRemoveUpcoming: (Int) -> Unit,
    onReorderManual: (List<QueuedTrack>) -> Unit,
    onReorderUpcoming: (List<Track>) -> Unit,
    onClearManual: () -> Unit,
    remote: Boolean = false,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = SukifyColors.Elevated) {
        QueueContent(
            state, onPlayManual, onPlayUpcoming, onRemoveManual, onRemoveUpcoming,
            onReorderManual, onReorderUpcoming, onClearManual, remote,
        )
    }
}

private sealed interface QueueRow {
    val key: String

    data class Header(val title: String, val action: String? = null) : QueueRow {
        override val key get() = "h:$title"
    }

    data class Now(val track: Track) : QueueRow {
        override val key get() = "now"
    }

    data class Manual(val item: QueuedTrack) : QueueRow {
        override val key get() = "m:${item.qid}"
    }

    data class Upcoming(val index: Int, val track: Track) : QueueRow {
        override val key get() = "u:$index:${track.id}"
    }
}

@Composable
fun QueueContent(
    state: PlayerState,
    onPlayManual: (Long) -> Unit,
    onPlayUpcoming: (Int) -> Unit,
    onRemoveManual: (Long) -> Unit,
    onRemoveUpcoming: (Int) -> Unit,
    onReorderManual: (List<QueuedTrack>) -> Unit,
    onReorderUpcoming: (List<Track>) -> Unit,
    onClearManual: () -> Unit,
    /** Очередь другого устройства (Connect): переставлять и чистить её отсюда нельзя. */
    remote: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    // Локальная копия порядка — перетаскивание отражаем сразу, плееру
    // отдаём итог по отпусканию пальца.
    var manual by remember(state.queue.manual) { mutableStateOf(state.queue.manual.toList()) }
    var upcoming by remember(state.queue.upcoming) { mutableStateOf(state.queue.upcoming.take(UPCOMING_SHOWN)) }

    val rows = buildList {
        state.track?.let {
            add(QueueRow.Header("Сейчас играет"))
            add(QueueRow.Now(it))
        }
        if (manual.isNotEmpty()) {
            add(QueueRow.Header("Дальше в очереди", if (remote) null else "Очистить"))
            manual.forEach { add(QueueRow.Manual(it)) }
        }
        if (upcoming.isNotEmpty()) {
            add(QueueRow.Header("Далее из: ${state.queue.contextName.ifBlank { "текущего списка" }}"))
            upcoming.forEachIndexed { i, t -> add(QueueRow.Upcoming(i, t)) }
        }
    }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = rows.getOrNull(from.index)
        val b = rows.getOrNull(to.index)
        when {
            a is QueueRow.Manual && b is QueueRow.Manual -> {
                manual = manual.toMutableList().apply { add(indexOf(b.item), removeAt(indexOf(a.item))) }
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
            a is QueueRow.Upcoming && b is QueueRow.Upcoming -> {
                upcoming = upcoming.toMutableList().apply { add(b.index, removeAt(a.index)) }
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }
        }
    }

    Column(Modifier.fillMaxHeight(0.92f).navigationBarsPadding()) {
        Text("Очередь", style = SukifyType.Title, color = SukifyColors.Text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                when (row) {
                    is QueueRow.Header -> Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.title, style = SukifyType.BodyBold, color = SukifyColors.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        row.action?.let {
                            Text(it, style = SukifyType.BodySmallBold, color = SukifyColors.TextSubdued, modifier = Modifier.bounceClick(onClick = onClearManual).padding(8.dp))
                        }
                    }
                    is QueueRow.Now -> QueueTrackRow(row.track, playing = true, onClick = {}, dragHandle = null)
                    is QueueRow.Manual -> ReorderableItem(reorder, key = row.key) { dragging ->
                        Dismissible(onDismiss = { onRemoveManual(row.item.qid) }) {
                            QueueTrackRow(
                                track = row.item.track,
                                playing = false,
                                dragging = dragging,
                                onClick = { onPlayManual(row.item.qid) },
                                dragHandle = if (remote) null else Modifier.draggableHandle(onDragStopped = { onReorderManual(manual) }),
                            )
                        }
                    }
                    is QueueRow.Upcoming -> ReorderableItem(reorder, key = row.key) { dragging ->
                        Dismissible(onDismiss = { onRemoveUpcoming(row.index) }) {
                            QueueTrackRow(
                                track = row.track,
                                playing = false,
                                dragging = dragging,
                                onClick = { onPlayUpcoming(row.index) },
                                dragHandle = if (remote) {
                                    null
                                } else {
                                    Modifier.draggableHandle(onDragStopped = {
                                        onReorderUpcoming(upcoming + state.queue.upcoming.drop(UPCOMING_SHOWN))
                                    })
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Смахнуть влево — убрать из очереди. */
@Composable
private fun Dismissible(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDismiss()
            value == SwipeToDismissBoxValue.EndToStart
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxWidth().fillMaxHeight().background(SukifyColors.Error), contentAlignment = Alignment.CenterEnd) {
                Text("Убрать", style = SukifyType.BodySmallBold, color = Color.White, modifier = Modifier.padding(end = 24.dp))
            }
        },
    ) {
        content()
    }
}

@Composable
private fun QueueTrackRow(
    track: Track,
    playing: Boolean,
    onClick: () -> Unit,
    dragHandle: Modifier?,
    dragging: Boolean = false,
) {
    val elevation by animateDpAsState(if (dragging) 12.dp else 0.dp, label = "drag")
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(elevation, RoundedCornerShape(6.dp))
            .background(if (dragging) SukifyColors.CardPressed else SukifyColors.Elevated)
            .bounceClick(pressed = 0.98f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(track.cover, size = 48.dp, shape = RoundedCornerShape(2.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = SukifyType.Body, color = if (playing) SukifyColors.Accent else SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artistNames, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (dragHandle != null) {
            Box(dragHandle.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(SukifyIcons.List, "Переставить", tint = SukifyColors.TextSubdued, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private const val UPCOMING_SHOWN = 100
