package ru.nepalimsya.sukify.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.ui.downloads.LocalDownloads
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.LocalAudible
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.ui.theme.drawLiquidPair

/**
 * Строка трека (TrackRow.vue в мобильной раскладке): обложка, название
 * (зелёное, если играет), исполнители с бейджами, ⋯. Трек без звука —
 * приглушён и не нажимается. Свайп вправо — «в очередь», как в Spotify.
 */
@Composable
fun TrackRow(
    track: Track,
    playing: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    showCover: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    downloaded: Boolean? = null,
    subtitle: String? = null,
    onSwipeQueue: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** Пришли по названию из плеера: строка вспыхивает и плавно гаснет. */
    highlight: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val downloads = LocalDownloads.current
    val isDownloaded = downloaded ?: downloads.isDownloaded(track.id)
    // Без сети — только скачанное: остальное приглушено и не нажимается.
    val available = downloads.isAvailable(track)
    val threshold = with(density) { 88.dp.toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val dragState = rememberDraggableState { delta ->
        // Тянется с сопротивлением и не дальше полутора порогов.
        dragX = (dragX + delta * 0.6f).coerceIn(0f, threshold * 1.5f)
        val nowArmed = dragX >= threshold
        if (nowArmed != armed) {
            armed = nowArmed
            if (nowArmed) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }
    }

    val flash = remember { Animatable(0f) }
    LaunchedEffect(highlight) {
        if (!highlight) return@LaunchedEffect
        flash.snapTo(0.3f)
        flash.animateTo(0.12f, tween(500))
        delay(1600)
        flash.animateTo(0f, tween(1400))
    }

    Box(modifier.fillMaxWidth()) {
        if (onSwipeQueue != null) {
            // Под строкой: кружок «в очередь» вытягивается каплей из края экрана
            // и растёт по мере свайпа; прошёл порог — капля отрывается от края
            // и зеленеет.
            val blob by animateColorAsState(if (armed) SukifyColors.Accent else SukifyColors.CardPressed, label = "queueBlob")
            val edge by animateFloatAsState(
                if (armed) 0f else 1f,
                spring(dampingRatio = 0.55f, stiffness = 420f),
                label = "queueEdge",
            )
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = (dragX / threshold).coerceIn(0f, 1f) }
                    .drawBehind {
                        val p = (dragX / threshold).coerceIn(0f, 1f)
                        val y = size.height / 2f
                        drawLiquidPair(
                            c1 = Offset(-4.dp.toPx(), y),
                            r1 = 16.dp.toPx() * (1f - 0.35f * p) * edge,
                            c2 = Offset(38.dp.toPx(), y),
                            r2 = 18.dp.toPx() * (0.6f + 0.4f * p),
                            color = blob,
                            stickiness = 3f,
                        )
                    }
                    .padding(start = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .graphicsLayer {
                            val p = (dragX / threshold).coerceIn(0f, 1f)
                            scaleX = 0.6f + 0.4f * p
                            scaleY = 0.6f + 0.4f * p
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(SukifyIcons.Queue, null, tint = if (armed) Color.Black else SukifyColors.Text, modifier = Modifier.size(18.dp))
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(dragX.roundToInt(), 0) }
                .drawBehind {
                    if (dragX > 0f) drawRect(SukifyColors.Elevated)
                    if (flash.value > 0f) drawRect(Color.White.copy(alpha = flash.value))
                }
                .then(
                    if (onSwipeQueue != null && available) {
                        Modifier.draggable(
                            state = dragState,
                            orientation = Orientation.Horizontal,
                            onDragStopped = {
                                if (armed) onSwipeQueue()
                                armed = false
                                animate(dragX, 0f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f)) { value, _ -> dragX = value }
                            },
                        )
                    } else Modifier,
                )
                .alpha(if (available) 1f else 0.4f)
                .combinedClickable(
                    enabled = available,
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMore()
                    },
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()
            if (showCover) {
                CoverImage(track.cover, size = 48.dp, shape = RoundedCornerShape(2.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playing) {
                        PlayingBars(playing = LocalAudible.current.value, size = 12.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        track.title,
                        style = SukifyType.Body,
                        color = if (playing) SukifyColors.Accent else SukifyColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isDownloaded) {
                        Icon(SukifyIcons.DownloadCircle, "Скачан", tint = SukifyColors.Accent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    if (track.previewOnly) Badge("30 сек")
                    if (track.unofficial) Badge("EXCL")
                    Text(
                        subtitle ?: track.artistNames,
                        style = SukifyType.BodySmall,
                        color = SukifyColors.TextSubdued,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
            Box(
                Modifier.size(40.dp).bounceClick(pressed = 0.85f, onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(SukifyIcons.More, "Меню", tint = SukifyColors.TextSubdued, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Плашка «30 сек» (превью Deezer) / «EXCL» (трека нет на площадках). */
@Composable
private fun Badge(text: String) {
    Text(
        text,
        style = SukifyType.Tiny,
        color = SukifyColors.Elevated,
        modifier = Modifier
            .padding(end = 6.dp)
            .background(SukifyColors.TextSubdued, RoundedCornerShape(2.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
