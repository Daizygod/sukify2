package ru.nepalimsya.sukify.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyMotion
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.util.Format

/**
 * Полоса перемотки «Сейчас играет»: тонкая линия, при касании — утолщается и
 * появляется бегунок; пока палец на полосе, время показывает точку под ним.
 * Позиция читается в отрисовке — полоса не рекомпозирует экран на каждом кадре.
 */
@Composable
fun SeekBar(
    position: () -> Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val thickness by animateFloatAsState(if (dragging) 6f else 4f, SukifyMotion.standard(), label = "bar")

    fun fraction(): Float =
        if (dragging) dragFraction else if (duration > 0) (position().toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSeek((f * duration).toLong())
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            onSeek((dragFraction * duration).toLong())
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val h = thickness.dp.toPx()
                val y = (size.height - h) / 2
                val r = CornerRadius(h / 2)
                drawRoundRect(color.copy(alpha = 0.3f), Offset(0f, y), Size(size.width, h), r)
                val w = size.width * fraction()
                drawRoundRect(color, Offset(0f, y), Size(w, h), r)
                if (dragging) {
                    drawCircle(color, radius = 7.dp.toPx(), center = Offset(w, size.height / 2))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val shownMs by remember(duration) {
                derivedStateOf { if (dragging) (dragFraction * duration).toLong() else position() }
            }
            // Подписи меняются раз в секунду — читаем через derivedStateOf с округлением.
            val seconds by remember { derivedStateOf { shownMs / 1000 } }
            Text(Format.duration(seconds * 1000), style = SukifyType.Label, color = SukifyColors.TextSubdued)
            Box(Modifier.weight(1f))
            Text(Format.duration(duration), style = SukifyType.Label, color = SukifyColors.TextSubdued)
        }
    }
}
