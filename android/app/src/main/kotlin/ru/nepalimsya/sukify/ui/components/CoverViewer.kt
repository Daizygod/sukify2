package ru.nepalimsya.sukify.ui.components

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.domain.Cover
import ru.nepalimsya.sukify.ui.icons.SukifyIcons

/** Что открыть крупно и откуда она вылетает (рамка обложки на экране). */
class CoverRequest(
    val cover: Cover,
    val original: String?,
    val title: String,
    val from: Rect,
    val round: Boolean,
    /** Кто открыл — эта обложка прячется, пока её копия на экране. */
    val source: Any,
)

/** Просмотрщик обложек — один на всё приложение (см. [CoverViewerHost]). */
@Stable
class CoverViewerState {
    var request by mutableStateOf<CoverRequest?>(null)
        internal set

    fun open(request: CoverRequest) {
        this.request = request
    }
}

val LocalCoverViewer = staticCompositionLocalOf<CoverViewerState?> { null }

/**
 * Обложка во весь экран (как CoverLightbox на сайте). Тап по обложке в шапке
 * альбома или плейлиста — она вылетает из своего места в центр экрана, чуть
 * «пружиня», как капля, а весь интерфейс за ней обесцвечивается и
 * размывается. Щипок и двойной тап увеличивают, увеличенную можно двигать.
 * Закрыть — тап мимо, ✕ или «назад»: обложка улетает обратно в шапку.
 */
@Composable
fun CoverViewerHost(content: @Composable () -> Unit) {
    val state = remember { CoverViewerState() }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val request = state.request

    LaunchedEffect(request) {
        if (request != null) progress.animateTo(1f, OpenSpring)
    }

    CompositionLocalProvider(LocalCoverViewer provides state) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = progress.value.coerceIn(0f, 1f)
                        renderEffect = if (p > 0.01f) backdropEffect(p, BackdropBlur.toPx()) else null
                    },
            ) {
                content()
            }
            if (request != null) {
                CoverOverlay(
                    request = request,
                    progress = progress,
                    onClosed = { state.request = null },
                    close = { onDone ->
                        scope.launch {
                            progress.animateTo(0f, closeSpring())
                            onDone()
                        }
                    },
                )
            }
        }
    }
}

/** Фон под обложкой: обесцвечен и чуть размыт ровно настолько, насколько она раскрыта. */
private fun backdropEffect(p: Float, blurPx: Float): androidx.compose.ui.graphics.RenderEffect {
    val gray = android.graphics.ColorMatrix().apply { setSaturation(1f - p) }
    val blur = blurPx * p
    return RenderEffect.createChainEffect(
        RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(gray)),
        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP),
    ).asComposeRenderEffect()
}

@Composable
private fun CoverOverlay(
    request: CoverRequest,
    progress: Animatable<Float, *>,
    onClosed: () -> Unit,
    close: (onDone: () -> Unit) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    // Увеличение и сдвиг увеличенной обложки. Жест меняет их сразу, в том же
    // кадре; плавные переходы (двойной тап, закрытие) — одной задачей.
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoomJob by remember { mutableStateOf<Job?>(null) }

    fun animateZoom(toZoom: Float, toPan: Offset, spec: AnimationSpec<Float>) {
        zoomJob?.cancel()
        val fromZoom = zoom
        val fromPan = pan
        zoomJob = scope.launch {
            animate(0f, 1f, animationSpec = spec) { t, _ ->
                zoom = lerp(fromZoom, toZoom, t)
                pan = Offset(lerp(fromPan.x, toPan.x, t), lerp(fromPan.y, toPan.y, t))
            }
        }
    }

    fun dismiss() {
        if (closing) return
        closing = true
        animateZoom(1f, Offset.Zero, closeSpring())
        close(onClosed)
    }

    BackHandler(onBack = ::dismiss)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // По центру, целиком, с небольшими полями по бокам.
        val side = with(density) { min(w - 2 * 16.dp.toPx(), h - 2 * 96.dp.toPx()) }
        val target = Rect(Offset((w - side) / 2f, (h - side) / 2f), androidx.compose.ui.geometry.Size(side, side))
        val corner = with(density) { 10.dp.toPx() }
        val fromCorner = if (request.round) request.from.width / 2f else with(density) { 4.dp.toPx() }

        // Затемнение поверх обесцвеченного фона; тап по нему закрывает.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.3f))
                .pointerInput(Unit) { detectTapGestures(onTap = { dismiss() }) },
        )

        Box(
            Modifier
                .offset { IntOffset(target.left.roundToInt(), target.top.roundToInt()) }
                .size(with(density) { side.toDp() })
                .graphicsLayer {
                    val p = progress.value
                    val pc = p.coerceIn(0f, 1f)
                    // Текущая рамка — между местом в шапке и центром экрана.
                    val curW = lerp(request.from.width, target.width, p)
                    val curCenter = Offset(
                        lerp(request.from.center.x, target.center.x, p),
                        lerp(request.from.center.y, target.center.y, p),
                    )
                    val base = curW / target.width
                    // В полёте обложка чуть вытягивается, как капля.
                    val wobble = 0.06f * sin(PI.toFloat() * pc)
                    scaleX = base * zoom * (1f - wobble)
                    scaleY = base * zoom * (1f + wobble)
                    translationX = curCenter.x - target.center.x + pan.x
                    translationY = curCenter.y - target.center.y + pan.y
                    shadowElevation = 24.dp.toPx() * pc
                    // Скругление задаём в экранных точках — делим на масштаб слоя.
                    shape = RoundedCornerShape(lerp(fromCorner, corner, pc) / (base * zoom).coerceAtLeast(0.01f))
                    clip = true
                }
                .pointerInput(side) {
                    detectTapGestures(
                        // Тап по самой обложке не закрывает — как на сайте.
                        onTap = {},
                        onDoubleTap = { tap ->
                            if (zoom > 1.01f) {
                                animateZoom(1f, Offset.Zero, zoomSpring())
                            } else {
                                val center = Offset(size.width / 2f, size.height / 2f)
                                animateZoom(DOUBLE_TAP_SCALE, clamp((center - tap) * (DOUBLE_TAP_SCALE - 1f), DOUBLE_TAP_SCALE, side), zoomSpring())
                            }
                        },
                    )
                }
                .pointerInput(side) {
                    detectTransformGestures { _, delta, gestureZoom, _ ->
                        if (closing) return@detectTransformGestures
                        zoomJob?.cancel()
                        val z = (zoom * gestureZoom).coerceIn(1f, MAX_SCALE)
                        zoom = z
                        pan = if (z <= 1.01f) Offset.Zero else clamp(pan + delta * z, z, side)
                    }
                },
        ) {
            val context = LocalContext.current
            // Подложка — копия, которая уже в кэше после экрана; поверх — оригинал.
            AsyncImage(
                model = request.cover.url(640),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            AsyncImage(
                model = ImageRequest.Builder(context).data(request.original ?: request.cover.url(Int.MAX_VALUE)).crossfade(200).build(),
                contentDescription = request.title,
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
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .bounceClick(onClick = ::dismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(SukifyIcons.Close, "Закрыть", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** Увеличенную обложку двигаем, но не дальше её краёв. */
private fun clamp(value: Offset, scale: Float, side: Float): Offset {
    val max = side * (scale - 1f) / 2f
    return Offset(value.x.coerceIn(-max, max), value.y.coerceIn(-max, max))
}

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

private val OpenSpring = spring<Float>(dampingRatio = 0.72f, stiffness = 320f)
private fun <T> closeSpring() = spring<T>(dampingRatio = 0.9f, stiffness = 520f)
private fun zoomSpring() = spring<Float>(dampingRatio = 0.85f, stiffness = 500f)
private val BackdropBlur = 6.dp
private const val DOUBLE_TAP_SCALE = 2.5f
private const val MAX_SCALE = 5f
