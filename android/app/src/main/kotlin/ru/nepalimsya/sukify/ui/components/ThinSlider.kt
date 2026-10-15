package ru.nepalimsya.sukify.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import kotlin.math.roundToInt
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyMotion

/**
 * Тонкий ползунок как в Spotify: полоса 4 dp, при касании толще и с
 * бегунком. [steps] > 0 — значение прилипает к делениям (с хаптикой).
 * [onChange] — пока тянем, [onFinished] — когда отпустили.
 */
@Composable
fun ThinSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onFinished: () -> Unit = {},
    color: Color = SukifyColors.Accent,
) {
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(value) }
    val currentChange by rememberUpdatedState(onChange)
    val currentFinished by rememberUpdatedState(onFinished)
    val thickness by animateFloatAsState(if (dragging) 6f else 4f, SukifyMotion.standard(), label = "slider")
    val span = (range.endInclusive - range.start).takeIf { it > 0 } ?: 1f

    fun valueAt(x: Float, width: Int): Float {
        val raw = range.start + (x / width).coerceIn(0f, 1f) * span
        if (steps <= 0) return raw
        val stepSize = span / (steps + 1)
        return range.start + ((raw - range.start) / stepSize).roundToInt() * stepSize
    }

    fun emit(v: Float) {
        if (v != dragValue && steps > 0) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        dragValue = v
        currentChange(v)
    }

    val shown = if (dragging) dragValue else value
    Box(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(range, steps) {
                detectTapGestures { offset ->
                    emit(valueAt(offset.x, size.width))
                    currentFinished()
                }
            }
            .pointerInput(range, steps) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragValue = value
                        emit(valueAt(offset.x, size.width))
                    },
                    onDragEnd = {
                        dragging = false
                        currentFinished()
                    },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        emit(valueAt(change.position.x, size.width))
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val h = thickness.dp.toPx()
            val y = (size.height - h) / 2
            val r = CornerRadius(h / 2)
            drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, y), Size(size.width, h), r)
            val w = size.width * ((shown - range.start) / span).coerceIn(0f, 1f)
            drawRoundRect(color, Offset(0f, y), Size(w, h), r)
            drawCircle(Color.White, radius = (if (dragging) 8 else 6).dp.toPx(), center = Offset(w, size.height / 2))
        }
    }
}
