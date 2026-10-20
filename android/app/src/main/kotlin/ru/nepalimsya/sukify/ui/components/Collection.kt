package ru.nepalimsya.sukify.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.LocalPlayback
import ru.nepalimsya.sukify.ui.shell.bottomChromePadding
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyMotion
import ru.nepalimsya.sukify.ui.theme.SukifyTitleFamily
import ru.nepalimsya.sukify.ui.theme.SukifyType
import kotlin.math.roundToInt

/** Высота верхней панели коллекции (без статус-бара). */
val CollectionBarHeight = 56.dp

/**
 * Где в шапке подборки заголовок и ряд с ▶ — от них, а не от низа шапки,
 * проявляется верхняя панель: под шапкой бывают ещё кнопки (плейлист), и
 * заголовок успевал заехать под прозрачную панель.
 */
@Stable
class CollectionAnchors {
    internal var hero: LayoutCoordinates? = null
    internal var title: LayoutCoordinates? = null
    internal var actions: LayoutCoordinates? = null
    internal val titleTop = mutableIntStateOf(-1)
    internal val titleBottom = mutableIntStateOf(-1)
    internal val actionsBottom = mutableIntStateOf(-1)

    internal fun update() {
        val h = hero?.takeIf { it.isAttached } ?: return
        title?.takeIf { it.isAttached }?.let {
            titleTop.intValue = h.localPositionOf(it, Offset.Zero).y.roundToInt()
            titleBottom.intValue = h.localPositionOf(it, Offset(0f, it.size.height.toFloat())).y.roundToInt()
        }
        actions?.takeIf { it.isAttached }?.let {
            actionsBottom.intValue = h.localPositionOf(it, Offset(0f, it.size.height.toFloat())).y.roundToInt()
        }
    }
}

val LocalCollectionAnchors = staticCompositionLocalOf<CollectionAnchors?> { null }

/** Большая зелёная ▶/⏸ (play-btn--lg в вебе). */
@Composable
fun BigPlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp, loading: Boolean = false) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .size(size)
            .bounceClick(pressed = 0.92f) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                onClick()
            }
            .background(SukifyColors.Accent, CircleShape)
            .semantics { contentDescription = if (playing) "Пауза" else "Слушать" },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(size * 0.4f), color = Color.Black, strokeWidth = 2.dp)
        } else {
            AnimatedContent(
                targetState = playing,
                transitionSpec = { (scaleIn(SukifyMotion.bouncy(), 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                label = "play",
            ) { isPlaying ->
                Icon(
                    if (isPlaying) SukifyIcons.PauseBig else SukifyIcons.PlayBig,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(size * 0.43f),
                )
            }
        }
    }
}

/** ▶ подборки: своя играет — пауза/продолжить, иначе включить с начала. */
@Composable
fun ContextPlayButton(contextKey: String, name: String, tracks: List<Track>?, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val playback = LocalPlayback.current
    BigPlayButton(
        playing = playback.isPlaying(contextKey),
        onClick = { playback.contexts.toggle(contextKey, name, tracks) },
        modifier = modifier,
        size = size,
    )
}

/** «В случайном порядке»: включает перемешивание и подборку (playShuffled в вебе). */
@Composable
fun ShuffleAction(contextKey: String, name: String, tracks: List<Track>?) {
    val playback = LocalPlayback.current
    IconAction(
        icon = SukifyIcons.ShuffleBig,
        description = "В случайном порядке",
        tint = if (playback.current.queue.shuffle) SukifyColors.Accent else SukifyColors.TextSubdued,
        onClick = { playback.contexts.toggle(contextKey, name, tracks, shuffle = true) },
    )
}

/** Круглая иконка-действие в ряду под шапкой: ⊕, ⬇, ⋯, перемешать. */
@Composable
fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = SukifyColors.TextSubdued,
    size: Dp = 28.dp,
) {
    val color by animateColorAsState(tint, label = "action")
    Box(
        modifier = modifier
            .size(size + 16.dp)
            .bounceClick(pressed = 0.85f, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size))
    }
}

/** Кнопка «назад» на тёмной подложке — видна на любой обложке. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(36.dp)
            .bounceClick(pressed = 0.88f, onClick = onClick)
            .background(Color.Black.copy(alpha = 0.32f), CircleShape)
            .semantics { contentDescription = "Назад" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(SukifyIcons.ChevronRight, null, tint = SukifyColors.Text, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 180f })
    }
}

/**
 * Экран подборки (альбом, плейлист, «Любимые», микс): градиент из цвета
 * обложки, шапка уезжает вверх, а верхняя панель на её месте набирает цвет
 * и название; когда ряд действий уходит под панель, в ней появляется ▶.
 * Прокрутка читается только в фазе отрисовки — кадры не пересобираются.
 */
@Composable
fun CollectionScaffold(
    color: Color,
    title: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    stickyPlay: (@Composable () -> Unit)? = null,
    topActions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    content: LazyListScope.(heroModifier: Modifier) -> Unit,
) {
    val navigator = LocalNavigator.current
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barPx = with(density) { (CollectionBarHeight + statusTop).toPx() }
    val heroHeight = remember { mutableIntStateOf(0) }
    val anchors = remember { CollectionAnchors() }

    // Фон панели: набирается, пока заголовок шапки подъезжает к панели, и
    // плотный к моменту, когда он под неё уходит. Без якорей — по низу шапки.
    val barAlpha by remember(state) {
        derivedStateOf {
            val top = anchors.titleTop.intValue.takeIf { it >= 0 }?.toFloat() ?: (heroHeight.intValue - barPx * 1.0f)
            when {
                state.firstVisibleItemIndex > 0 -> 1f
                heroHeight.intValue <= 0 -> 0f
                else -> ((state.firstVisibleItemScrollOffset - (top - barPx * 2.2f)) / (barPx * 1.2f)).coerceIn(0f, 1f)
            }
        }
    }
    // Название в панели — пока большой заголовок уходит под неё, без дубля.
    val titleAlpha by remember(state) {
        derivedStateOf {
            val top = anchors.titleTop.intValue
            val bottom = anchors.titleBottom.intValue
            when {
                state.firstVisibleItemIndex > 0 -> 1f
                top < 0 || bottom <= top -> barAlpha
                else -> ((state.firstVisibleItemScrollOffset - (top - barPx)) / (bottom - top).toFloat()).coerceIn(0f, 1f)
            }
        }
    }
    // Ряд с ▶ ушёл под панель наполовину — показываем ▶ в панели.
    val showStickyPlay by remember(state) {
        derivedStateOf {
            val bottom = anchors.actionsBottom.intValue.takeIf { it >= 0 } ?: heroHeight.intValue
            state.firstVisibleItemIndex > 0 ||
                (bottom > 0 && state.firstVisibleItemScrollOffset > bottom - barPx - with(density) { 30.dp.toPx() })
        }
    }
    val barColor = lerp(color, Color.Black, 0.45f)

    Box(modifier.fillMaxSize().background(SukifyColors.Elevated)) {
        CompositionLocalProvider(LocalCollectionAnchors provides anchors) {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().testTag("collection_list"),
                contentPadding = PaddingValues(bottom = bottomChromePadding(24.dp)),
            ) {
                content(
                    Modifier
                        .onSizeChanged { heroHeight.intValue = it.height }
                        .onPlaced { anchors.hero = it; anchors.update() },
                )
            }
        }

        // Верхняя панель: фон и название проявляются по мере прокрутки.
        Box(
            Modifier
                .fillMaxWidth()
                .height(CollectionBarHeight + statusTop)
                .graphicsLayer { alpha = barAlpha }
                .background(barColor),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = statusTop)
                .height(CollectionBarHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = { navigator.back() })
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                style = SukifyType.BodyBold,
                color = SukifyColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        alpha = titleAlpha
                        translationY = (1f - titleAlpha) * 12.dp.toPx()
                    },
            )
            topActions()
            if (stickyPlay != null) {
                AnimatedVisibility(
                    visible = showStickyPlay,
                    enter = scaleIn(SukifyMotion.bouncy(), 0.5f) + fadeIn(),
                    exit = scaleOut(targetScale = 0.5f) + fadeOut(),
                ) {
                    Box(Modifier.padding(start = 8.dp)) { stickyPlay() }
                }
            }
        }
        overlay()
    }
}

/**
 * Шапка подборки (CollectionHero в мобильной раскладке): обложка по центру,
 * под ней название, мета и ряд действий. Фон — цвет обложки, уходящий в
 * #121212, как продолжение градиента веба.
 */
@Composable
fun CollectionHero(
    color: Color,
    title: String,
    modifier: Modifier = Modifier,
    cover: @Composable () -> Unit,
    meta: @Composable ColumnScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
    play: @Composable () -> Unit,
) {
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val anchors = LocalCollectionAnchors.current
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to color,
                    0.55f to lerp(color, SukifyColors.Elevated, 0.6f),
                    1f to SukifyColors.Elevated,
                ),
            )
            .padding(top = statusTop + CollectionBarHeight),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp), contentAlignment = Alignment.Center) {
            cover()
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                title,
                style = SukifyType.Hero.copy(fontSize = 26.sp, fontFamily = SukifyTitleFamily, fontWeight = FontWeight.Bold),
                color = SukifyColors.Text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.onPlaced { anchors?.title = it; anchors?.update() },
            )
            Spacer(Modifier.height(8.dp))
            meta()
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 8.dp)
                .onPlaced { anchors?.actions = it; anchors?.update() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = actions)
            play()
        }
    }
}

/** Обложку шапки можно открыть крупно: оригинал (если есть) и подпись для TalkBack. */
class CoverZoom(val original: String?, val title: String)

/**
 * Обложка шапки: 62% ширины экрана, не больше 260 dp, с тенью. С [zoom] тап
 * открывает её во весь экран (CoverViewerHost) — копия вылетает отсюда, а
 * сама обложка на это время прячется, будто это она и улетела.
 */
@Composable
fun HeroCover(
    cover: ru.nepalimsya.sukify.domain.Cover?,
    round: Boolean = false,
    fallback: ImageVector = SukifyIcons.Album,
    zoom: CoverZoom? = null,
) {
    val width = LocalConfiguration.current.screenWidthDp
    val size = (width * 0.62f).dp.coerceAtMost(260.dp)
    val shape = if (round) CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
    val viewer = LocalCoverViewer.current
    val source = remember { Any() }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val shown = viewer?.request?.source !== source
    Box(
        Modifier
            .size(size)
            // Рамка целиком, даже если шапка уже уехала за край, и с учётом масштаба шапки.
            .onGloballyPositioned {
                bounds = Rect(it.localToRoot(Offset.Zero), it.localToRoot(Offset(it.size.width.toFloat(), it.size.height.toFloat())))
            }
            .graphicsLayer { alpha = if (shown) 1f else 0f }
            .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .then(
                if (zoom != null && cover != null && viewer != null) {
                    Modifier.bounceClick(pressed = 0.97f) {
                        viewer.open(CoverRequest(cover, zoom.original, zoom.title, bounds, round, source))
                    }
                } else Modifier,
            ),
    ) {
        FillCover(cover, pxHint = 640, modifier = Modifier.fillMaxSize(), shape = shape, fallbackIcon = fallback)
    }
}

/** Квадрат-плитка вместо обложки: «Любимые треки», микс дня. */
@Composable
fun HeroTile(brush: Brush, icon: ImageVector) {
    val width = LocalConfiguration.current.screenWidthDp
    val size = (width * 0.62f).dp.coerceAtMost(260.dp)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
    Box(
        Modifier
            .size(size)
            .shadow(24.dp, shape)
            .background(brush, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = SukifyColors.Text, modifier = Modifier.size(size * 0.32f))
    }
}

/** Строка меты: «Альбом • 2023» серым. */
@Composable
fun MetaText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, modifier = modifier, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Заглушка на время загрузки: пульсирующие серые блоки вместо пустоты. */
@Composable
fun LoadingBlock(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = SukifyColors.TextSubdued, strokeWidth = 2.dp)
    }
}

/** Ошибка вместо экрана: что случилось и «Повторить». */
@Composable
fun ErrorBlock(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(message, style = SukifyType.Body, color = SukifyColors.TextSubdued)
        OutlineButton("Повторить", onClick = onRetry)
    }
}
