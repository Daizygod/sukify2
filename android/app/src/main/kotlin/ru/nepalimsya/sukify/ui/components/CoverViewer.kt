package ru.nepalimsya.sukify.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.ui.icons.SukifyIcons

/**
 * Обложка во весь экран (как CoverLightbox на сайте): тап мимо, ✕ или «назад»
 * — закрыть. Под пальцы: щипок и двойной тап увеличивают, увеличенную можно
 * двигать. Пока грузится оригинал, видна уже загруженная копия поменьше.
 */
@Composable
fun CoverViewer(cover: Cover, original: String?, title: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(200)) }
        val scope = rememberCoroutineScope()
        fun close() {
            scope.launch {
                appear.animateTo(0f, tween(150))
                onDismiss()
            }
        }

        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var size by remember { mutableStateOf(IntSize.Zero) }

        /** Увеличенную обложку двигаем, но не дальше её краёв. */
        fun clamp(value: Offset, s: Float): Offset {
            val maxX = size.width * (s - 1f) / 2f
            val maxY = size.height * (s - 1f) / 2f
            return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = appear.value }
                .background(Color.Black.copy(alpha = 0.92f))
                .pointerInput(Unit) { detectTapGestures(onTap = { close() }) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .padding(20.dp)
                    .aspectRatio(1f)
                    .onSizeChanged { size = it }
                    .graphicsLayer {
                        scaleX = scale * (0.94f + 0.06f * appear.value)
                        scaleY = scale * (0.94f + 0.06f * appear.value)
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            // Тап по самой обложке не закрывает — как на сайте.
                            onTap = {},
                            onDoubleTap = { tap ->
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = DOUBLE_TAP_SCALE
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    offset = clamp((center - tap) * (DOUBLE_TAP_SCALE - 1f), DOUBLE_TAP_SCALE)
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            offset = if (scale <= 1.01f) Offset.Zero else clamp(offset + pan * scale, scale)
                        }
                    }
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                val context = LocalContext.current
                // Подложка — копия, которая уже в кэше после экрана релиза.
                AsyncImage(
                    model = cover.url(640),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                AsyncImage(
                    model = ImageRequest.Builder(context).data(original ?: cover.url(Int.MAX_VALUE)).crossfade(200).build(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .size(48.dp)
                    .bounceClick(onClick = { close() }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(SukifyIcons.Close, "Закрыть", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

private const val DOUBLE_TAP_SCALE = 2.5f
private const val MAX_SCALE = 5f
