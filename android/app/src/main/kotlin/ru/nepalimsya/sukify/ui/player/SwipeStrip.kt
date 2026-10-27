package ru.nepalimsya.sukify.ui.player

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.ui.theme.SukifyMotion
import ru.nepalimsya.sukify.ui.theme.drawLiquidNeck

/**
 * «Жидкая» лента: соседняя обложка подтягивается к текущей, между ними
 * натягивается перемычка цвета обложек, а сами обложки чуть «вдыхают».
 * [contentWidth] — ширина обложки внутри слайда (она стоит по центру).
 */
class StripLiquid<T>(val contentWidth: Dp, val color: (T) -> Color)

/**
 * Лента «предыдущий | текущий | следующий», которую тянут пальцем — как
 * карусель обложек и подпись мини-плеера в вебе. Отпустил дальше порога —
 * лента доезжает до соседа и трек переключается; ближе — пружинит назад.
 *
 * Палец двигает ленту синхронно, в том же кадре: раньше сдвиг уходил через
 * корутину и часть движений терялась — лента отставала от пальца вдвое и не
 * добирала порог. Слайды ключуются треком: после переключения тот же слайд
 * с уже загруженной картинкой становится центральным, а сдвиг обнуляется в
 * том же кадре, где сменился трек, — ничего не моргает.
 */
@Composable
fun <T : Any> SwipeStrip(
    previous: T?,
    current: T,
    next: T?,
    itemKey: (T) -> Any,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    thresholdDp: Int = 60,
    liquid: StripLiquid<T>? = null,
    /** Лента тронулась с места / вернулась в покой (жест вместе с доводкой). */
    onMoving: ((Boolean) -> Unit)? = null,
    slide: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val threshold = with(density) { thresholdDp.dp.toPx() }
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current

        val currentKey = itemKey(current)
        val liveKey = rememberUpdatedState(currentKey)
        // Сдвиг ленты и трек, к которому он относится.
        var offset by remember { mutableFloatStateOf(0f) }
        var anchor by remember { mutableStateOf(currentKey) }
        var settling by remember { mutableStateOf(false) }
        var job by remember { mutableStateOf<Job?>(null) }
        val hasNext = next != null
        val hasPrevious = previous != null

        /** Сдвиг, который видно: после смены трека старый сдвиг уже не в счёт. */
        fun shown(): Float = if (anchor == liveKey.value) offset else 0f

        val moving by remember { derivedStateOf { shown() != 0f } }
        val movingCallback by rememberUpdatedState(onMoving)
        LaunchedEffect(moving) { movingCallback?.invoke(moving) }

        // Трек сменился (доиграл, кнопка, свайп, другое устройство) — сдвиг в ноль.
        LaunchedEffect(currentKey) {
            if (anchor != currentKey) {
                job?.cancel()
                offset = 0f
                anchor = currentKey
                settling = false
            }
        }

        val drag = rememberDraggableState { delta ->
            if (settling) return@rememberDraggableState
            job?.cancel()
            var x = offset + delta
            // Туда, где трека нет, — с сопротивлением.
            if (x < 0 && !hasNext) x = x.coerceAtLeast(-threshold * 0.6f)
            if (x > 0 && !hasPrevious) x = x.coerceAtMost(threshold * 0.6f)
            offset = x
        }

        fun settle(velocity: Float) {
            val x = offset
            val fling = abs(velocity) > 1200f
            val toNext = (x < -threshold || (fling && velocity < 0 && x < 0)) && hasNext
            val toPrev = (x > threshold || (fling && velocity > 0 && x > 0)) && hasPrevious
            job?.cancel()
            job = scope.launch {
                if (toNext || toPrev) {
                    settling = true
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    animate(offset, if (toNext) -width else width, velocity, SukifyMotion.sheet()) { v, _ -> offset = v }
                    if (toNext) onNext() else onPrevious()
                    // Центровка — по смене трека (LaunchedEffect выше); не сменился — вернёмся сами.
                    delay(1500)
                    settling = false
                    animate(offset, 0f, 0f, SukifyMotion.sheet()) { v, _ -> offset = v }
                } else {
                    animate(offset, 0f, velocity, SukifyMotion.sheet()) { v, _ -> offset = v }
                }
            }
        }

        val slides = listOfNotNull(previous?.let { -1 to it }, 0 to current, next?.let { 1 to it })
        val keys = slides.map { itemKey(it.second) }
        val uniqueKeys = keys.toSet().size == keys.size

        val geometry = liquid?.let {
            StripGeometry(
                width = width,
                content = with(density) { it.contentWidth.toPx() },
                minGap = with(density) { 6.dp.toPx() },
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                // Соседи живут за краями ленты и видны только во время жеста. Режем
                // только по бокам: тень обложки снизу остаётся целой.
                .drawWithContent {
                    clipRect(top = -size.height, bottom = size.height * 2) { this@drawWithContent.drawContent() }
                }
                .then(
                    if (liquid != null && geometry != null) {
                        Modifier.drawBehind {
                            val x = shown()
                            if (x == 0f) return@drawBehind
                            val incoming = if (x < 0) next else previous
                            incoming ?: return@drawBehind
                            val dir = -sign(x) // +1 — справа въезжает следующий
                            val s = geometry.scale(x)
                            val half = geometry.content * s / 2f
                            val centerCurrent = width / 2f + x
                            val centerIncoming = width / 2f + dir * width + x - dir * geometry.pull(x)
                            val (leftCenter, rightCenter) = if (dir > 0) centerCurrent to centerIncoming else centerIncoming to centerCurrent
                            val (leftItem, rightItem) = if (dir > 0) current to incoming else incoming to current
                            drawLiquidNeck(
                                leftEdge = leftCenter + half,
                                rightEdge = rightCenter - half,
                                centerY = size.height / 2f,
                                height = geometry.content * s,
                                maxGap = geometry.restGap * 0.92f,
                                leftColor = liquid.color(leftItem),
                                rightColor = liquid.color(rightItem),
                            )
                        }
                    } else Modifier,
                )
                .draggable(
                    state = drag,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity -> settle(velocity) },
                ),
        ) {
            slides.forEachIndexed { index, (slot, item) ->
                key(if (uniqueKeys) keys[index] else slot) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                val x = shown()
                                var tx = slot * width + x
                                if (geometry != null && x != 0f) {
                                    // Въезжающий сосед подтягивается к текущей обложке.
                                    val dir = -sign(x)
                                    if (slot.toFloat() == dir) tx -= dir * geometry.pull(x)
                                    val s = geometry.scale(x)
                                    scaleX = s
                                    scaleY = s
                                }
                                translationX = tx
                            },
                    ) {
                        slide(item)
                    }
                }
            }
        }
    }
}

/** Как сосед подтягивается и как «дышат» обложки по ходу свайпа. */
private class StripGeometry(val width: Float, val content: Float, minGap: Float) {
    /** Щель между обложками в покое. */
    val restGap = (width - content).coerceAtLeast(0f)
    private val maxPull = (restGap - minGap).coerceAtLeast(0f)

    private fun progress(x: Float) = (abs(x) / width).coerceIn(0f, 1f)

    /** К середине жеста сосед почти касается текущей обложки, к концу — снова на месте. */
    fun pull(x: Float): Float = maxPull * sin(PI.toFloat() * progress(x))

    fun scale(x: Float): Float = 1f - 0.05f * sin(PI.toFloat() * progress(x))
}
