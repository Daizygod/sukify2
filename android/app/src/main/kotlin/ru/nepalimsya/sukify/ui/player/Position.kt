package ru.nepalimsya.sukify.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Позиция воспроизведения, обновляемая с частотой кадров, пока играет музыка.
 *
 * Читать её стоит в фазе отрисовки (drawBehind, graphicsLayer): тогда каждый
 * кадр перерисовывается только полоска, а не весь экран. На паузе цикл
 * засыпает и проверяет позицию раз в полсекунды (её могли сдвинуть извне).
 */
@Composable
fun rememberPlaybackPosition(playing: Boolean, read: () -> Long): State<Long> {
    val position = remember { mutableLongStateOf(read()) }
    LaunchedEffect(playing) {
        while (isActive) {
            if (playing) {
                withFrameMillis { position.longValue = read() }
            } else {
                position.longValue = read()
                delay(500)
            }
        }
    }
    return position
}
