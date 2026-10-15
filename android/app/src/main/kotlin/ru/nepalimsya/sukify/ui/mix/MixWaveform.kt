package ru.nepalimsya.sukify.ui.mix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import ru.nepalimsya.sukify.playback.mix.FilterSide
import ru.nepalimsya.sukify.playback.mix.MixShapes
import ru.nepalimsya.sukify.playback.mix.Points
import ru.nepalimsya.sukify.playback.mix.Waveform
import ru.nepalimsya.sukify.playback.mix.valueAt
import ru.nepalimsya.sukify.ui.theme.SukifyColors

/** Кривые стороны перехода: громкость, одна ведущая полоса эквалайзера, фильтр. */
data class SideCurves(val volume: Points, val eq: Points?, val filter: FilterSide?)

/** Сколько столбиков рисуем в панели — достаточно для телефона и дёшево. */
private const val COLUMNS = 180

/**
 * Окно вокруг перекрытия: по половине его длины с каждой стороны (не меньше
 * полутора секунд) — видно, что было до и что будет после. [zoom] < 1 — ближе.
 */
fun windowPadMs(lengthMs: Long, zoom: Float): Double = max(lengthMs * 0.5, 1500.0) * zoom

/**
 * Панель волны одного трека (MixWaveform.vue под палец): перекрытие
 * подсвечено, за краями трека — затемнение, сетка долей, три кривые.
 * Тянем волну пальцем (вправо — более раннее место), щипком — масштаб.
 */
@Composable
fun MixPane(
    mix: TrackMix?,
    startMs: Long,
    lengthMs: Long,
    zoom: Float,
    curves: SideCurves,
    playhead: Float?,
    onMove: (Long) -> Long,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 150.dp,
) {
    val haptics = LocalHapticFeedback.current
    val currentStart by rememberUpdatedState(startMs)
    val currentOnMove by rememberUpdatedState(onMove)
    val pad = windowPadMs(lengthMs, zoom)
    val windowMs = lengthMs + pad * 2
    var raw by remember { mutableDoubleStateOf(0.0) }
    var lastApplied by remember { mutableLongStateOf(Long.MIN_VALUE) }
    // Ширина панели нужна жесту, а знает её отрисовка — держим вне состояния Compose.
    val width = remember { DoubleArray(1) { 1.0 } }
    val layers = remember(mix?.waveform) { Array(mix?.waveform?.layerCount ?: 0) { FloatArray(COLUMNS) } }
    val paths = remember(mix?.waveform) { Array(layers.size) { Path() } }
    val curvePath = remember { Path() }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            // Щипок двумя пальцами — масштаб окна (одним пальцем — перетаскивание ниже).
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val z = event.calculateZoom()
                            if (z != 1f) onZoom(1f / z)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    raw -= delta * windowMs / width[0]
                    val applied = currentOnMove(max(0.0, raw).roundToLong())
                    // Прилипли к другой доле — лёгкий щелчок в руку.
                    if (applied != lastApplied && lastApplied != Long.MIN_VALUE) {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    }
                    lastApplied = applied
                },
                onDragStarted = {
                    raw = currentStart.toDouble()
                    lastApplied = currentStart
                },
            ),
    ) {
        width[0] = size.width.toDouble()
        val w = size.width
        val h = size.height
        val from = startMs - pad
        val to = startMs + lengthMs + pad
        val overlapX = (pad / windowMs * w).toFloat()
        val overlapW = (lengthMs / windowMs * w).toFloat()

        drawRect(SukifyColors.Card)
        // Подложка перекрытия — под волной: поверх она красила бы её.
        drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(overlapX, 0f), Size(overlapW, h), CornerRadius(6.dp.toPx()))

        val wave = mix?.waveform
        if (wave != null) {
            wave.columns(mix.durationMs, from, to, COLUMNS, layers)
            val colors = if (wave.layered) Waveform.LAYER_COLORS else Waveform.LEGACY_COLORS
            for (i in layers.indices) {
                buildBand(paths[i], layers[i], w, h)
                drawPath(paths[i], Color(colors[i]))
            }
        }

        // За краями трека звука нет — гасим.
        val duration = mix?.durationMs ?: 0
        val head = (((0 - from) / windowMs) * w).toFloat().coerceIn(0f, w)
        val tail = (((duration - from) / windowMs) * w).toFloat().coerceIn(0f, w)
        if (head > 0f) drawRect(Color.Black.copy(alpha = 0.55f), Offset.Zero, Size(head, h))
        if (tail < w) drawRect(Color.Black.copy(alpha = 0.55f), Offset(tail, 0f), Size(w - tail, h))

        // Доли: сильная (первая в такте) — ярче.
        mix?.beats?.let { beats ->
            for ((i, ms) in beats.withIndex()) {
                if (ms < from) continue
                if (ms > to) break
                val x = ((ms - from) / windowMs * w).toFloat()
                val strong = i % 4 == 0
                drawLine(
                    Color.White.copy(alpha = if (strong) 0.45f else 0.16f),
                    Offset(x, 0f),
                    Offset(x, h),
                    strokeWidth = if (strong) 1.5.dp.toPx() else 1.dp.toPx(),
                )
            }
        }

        // Кривые автоматизации внутри перекрытия.
        drawCurve(curvePath, curves.volume, overlapX, overlapW, h, Color(MixShapes.COLOR_VOLUME)) { v -> gainY(v, h) }
        curves.eq?.let { drawCurve(curvePath, it, overlapX, overlapW, h, Color(MixShapes.COLOR_EQ)) { v -> dbY(v, h) } }
        curves.filter?.let { drawCurve(curvePath, it.points, overlapX, overlapW, h, Color(MixShapes.COLOR_FILTER)) { v -> hzY(v, h) } }

        playhead?.let { p ->
            val x = overlapX + overlapW * p
            if (x in 0f..w) drawLine(Color.White, Offset(x, 0f), Offset(x, h), strokeWidth = 2.dp.toPx())
        }
    }
}

/** Обзор всего трека полоской: где сейчас перекрытие; тычок/протяжка — прыжок туда. */
@Composable
fun MixOverview(mix: TrackMix?, startMs: Long, lengthMs: Long, onJump: (Long) -> Unit, modifier: Modifier = Modifier) {
    val layers = remember(mix?.waveform) { Array(mix?.waveform?.layerCount ?: 0) { FloatArray(OVERVIEW_COLUMNS) } }
    val paths = remember(mix?.waveform) { Array(layers.size) { Path() } }
    val built = remember(mix?.waveform) { booleanArrayOf(false) }
    val currentOnJump by rememberUpdatedState(onJump)
    val duration = mix?.durationMs ?: 0
    val jump: (Float, Float) -> Unit = { x, width ->
        if (duration > 0 && width > 0) {
            val k = (x / width).coerceIn(0f, 1f)
            currentOnJump(max(0L, (k * duration - lengthMs / 2.0).roundToLong()))
        }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .pointerInput(duration, lengthMs) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    jump(down.position.x, size.width.toFloat())
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (change.pressed) {
                            jump(change.position.x, size.width.toFloat())
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val w = size.width
        val h = size.height
        drawRect(SukifyColors.Card)
        val wave = mix?.waveform
        if (wave != null && duration > 0) {
            // Весь трек — один раз: он не двигается.
            if (!built[0]) {
                wave.columns(duration, 0.0, duration.toDouble(), OVERVIEW_COLUMNS, layers)
                built[0] = true
            }
            val colors = if (wave.layered) Waveform.LAYER_COLORS else Waveform.LEGACY_COLORS
            for (i in layers.indices) {
                buildBand(paths[i], layers[i], w, h)
                drawPath(paths[i], Color(colors[i]).copy(alpha = 0.8f))
            }
            val winW = max(3f, lengthMs.toFloat() / duration * w)
            val winX = (startMs.toFloat() / duration * w).coerceIn(0f, w - winW)
            drawRect(Color.White.copy(alpha = 0.22f), Offset(winX, 0f), Size(winW, h))
            drawRect(Color.White, Offset(winX, 0f), Size(winW, h), style = Stroke(1.5.dp.toPx()))
        }
    }
}

private const val OVERVIEW_COLUMNS = 240

/** Зеркальная полоса из высот столбцов; NaN — разрыв (столбец вне трека). */
private fun buildBand(path: Path, heights: FloatArray, w: Float, h: Float) {
    path.rewind()
    val mid = h / 2
    val n = heights.size
    var start = -1
    var i = 0
    while (i <= n) {
        val inside = i < n && !heights[i].isNaN()
        if (inside && start < 0) start = i
        if (!inside && start >= 0) {
            // Кусок [start, i): верх слева направо, низ справа налево.
            for (j in start until i) {
                val x = j.toFloat() / (n - 1) * w
                val y = mid - heights[j] * mid
                if (j == start) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (j in i - 1 downTo start) {
                path.lineTo(j.toFloat() / (n - 1) * w, mid + heights[j] * mid)
            }
            path.close()
            start = -1
        }
        i++
    }
}

private inline fun DrawScope.drawCurve(
    path: Path,
    points: Points,
    x0: Float,
    width: Float,
    h: Float,
    color: Color,
    map: (Float) -> Float,
) {
    path.rewind()
    val steps = 90
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val x = x0 + width * t
        val y = map(points.valueAt(t))
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Громкость 0..1: 1 вверху. */
private fun gainY(v: Float, h: Float) = h * 0.08f + (1 - v.coerceIn(0f, 1f)) * h * 0.84f

/** Эквалайзер: 0 дБ вверху, −40 дБ у самого низа. */
private fun dbY(v: Float, h: Float) = h * 0.08f + min(1f, max(0f, -v / 40f)) * h * 0.84f

/** Частота — по логарифму, иначе весь ход прижат к одному краю. */
private fun hzY(v: Float, h: Float): Float {
    val k = ((log10(max(20f, v)) - log10(20f)) / (log10(20000f) - log10(20f))).coerceIn(0f, 1f)
    return h * 0.08f + (1 - k) * h * 0.84f
}
