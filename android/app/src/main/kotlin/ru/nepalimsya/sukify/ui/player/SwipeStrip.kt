package ru.nepalimsya.sukify.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.ui.theme.SukifyMotion

/**
 * Лента «предыдущий | текущий | следующий», которую тянут пальцем — как
 * карусель обложек и подпись мини-плеера в вебе. Отпустил дальше порога —
 * лента доезжает до соседа и трек переключается; ближе — пружинит назад.
 *
 * Слайды ключуются логической позицией, а не треком: после смены трека
 * соседний слайд с уже загруженной картинкой просто становится центральным,
 * поэтому ничего не моргает.
 */
@Composable
fun <T> SwipeStrip(
    previous: T?,
    current: T,
    next: T?,
    trackKey: Any?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    thresholdDp: Int = 60,
    slide: @Composable (T?) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        val threshold = with(LocalDensity.current) { thresholdDp.dp.toPx() }
        val offset = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current
        var settling by remember { mutableStateOf(false) }

        // Трек сменился (доиграл, кнопка, другое устройство) — центрируемся без анимации.
        LaunchedEffect(trackKey) {
            offset.snapTo(0f)
            settling = false
        }

        val drag = rememberDraggableState { delta ->
            if (settling) return@rememberDraggableState
            var x = offset.value + delta
            // Туда, где трека нет, — с сопротивлением.
            if (x < 0 && next == null) x = x.coerceAtLeast(-threshold * 0.6f)
            if (x > 0 && previous == null) x = x.coerceAtMost(threshold * 0.6f)
            scope.launch { offset.snapTo(x) }
        }

        Box(
            Modifier
                .fillMaxWidth()
                // Соседи живут за краями ленты и видны только во время жеста.
                .clipToBounds()
                .draggable(
                    state = drag,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        val x = offset.value
                        val fling = abs(velocity) > 1200f
                        val toNext = (x < -threshold || (fling && velocity < 0 && x < 0)) && next != null
                        val toPrev = (x > threshold || (fling && velocity > 0 && x > 0)) && previous != null
                        when {
                            toNext || toPrev -> {
                                settling = true
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                offset.animateTo(if (toNext) -width else width, SukifyMotion.sheet(), initialVelocity = velocity)
                                if (toNext) onNext() else onPrevious()
                                // Центровка — по смене trackKey (LaunchedEffect выше);
                                // если трек не сменился, вернёмся сами.
                                if (settling) {
                                    kotlinx.coroutines.delay(1500)
                                    if (settling) {
                                        settling = false
                                        offset.animateTo(0f, SukifyMotion.sheet())
                                    }
                                }
                            }
                            else -> offset.animateTo(0f, SukifyMotion.sheet(), initialVelocity = velocity)
                        }
                    },
                ),
        ) {
            listOf(-1 to previous, 0 to current, 1 to next).forEach { (slot, item) ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationX = slot * width + offset.value },
                ) {
                    if (slot == 0 || item != null) slide(item)
                }
            }
        }
    }
}
