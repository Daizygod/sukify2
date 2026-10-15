package ru.nepalimsya.sukify.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.domain.Track
import ru.nepalimsya.sukify.ui.components.CoverImage
import ru.nepalimsya.sukify.ui.components.LikeButton
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

val MiniPlayerHeight = 56.dp

/**
 * Мини-плеер над нижней панелью (MobileMiniPlayer.vue): карточка в цвет
 * обложки, подпись листается свайпом между треками, ♥ и ▶, тонкий прогресс.
 * Тап — «Сейчас играет».
 */
@Composable
fun MiniPlayer(
    track: Track,
    previous: Track?,
    next: Track?,
    playing: Boolean,
    liked: Boolean,
    duration: Long,
    position: () -> Long,
    remoteDevice: String?,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onToggleLike: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        if (remoteDevice != null && track.release?.color == null) RemoteColor else miniPlayerColor(track.coverColor()),
        tween(450),
        label = "mini",
    )
    Box(
        modifier = modifier
            .testTag("mini_player")
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .height(MiniPlayerHeight)
            .shadow(16.dp, RoundedCornerShape(6.dp), ambientColor = Color.Black, spotColor = Color.Black)
            .background(bg, RoundedCornerShape(6.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(MiniPlayerHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Обложка стоит на месте и меняется наплывом.
            AnimatedContent(
                targetState = track,
                contentKey = { it.id },
                transitionSpec = { fadeIn(tween(380)) togetherWith fadeOut(tween(380)) },
                label = "miniCover",
                modifier = coverModifier,
            ) { t ->
                CoverImage(t.cover, size = 40.dp)
            }
            Spacer(Modifier.width(10.dp))
            SwipeStrip(
                previous = previous,
                current = track,
                next = next,
                trackKey = track.id,
                onNext = onNext,
                onPrevious = onPrevious,
                modifier = Modifier.weight(1f),
                thresholdDp = 48,
            ) { t ->
                if (t != null) MiniLine(t, remoteDevice)
            }
            LikeButton(liked = liked, onToggle = onToggleLike, size = 24.dp)
            Box(
                Modifier.size(40.dp).bounceClick(pressed = 0.85f, onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) SukifyIcons.PauseBig else SukifyIcons.PlayBig,
                    contentDescription = if (playing) "Пауза" else "Слушать",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        ProgressLine(position, duration, Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun MiniLine(track: Track, remoteDevice: String?) {
    Column(Modifier.fillMaxWidth().padding(end = 8.dp)) {
        Text(
            track.title,
            style = SukifyType.BodySmallBold.copy(fontSize = SukifyType.Caption.fontSize),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (remoteDevice != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SukifyIcons.Devices, null, tint = SukifyColors.Accent, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text("Играет: $remoteDevice", style = SukifyType.Label, color = SukifyColors.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(
                track.artistNames,
                style = SukifyType.Label.copy(fontSize = SukifyType.Caption.fontSize),
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Двухпиксельная полоска прогресса — рисуется, а не рекомпозируется. */
@Composable
fun ProgressLine(position: () -> Long, duration: Long, modifier: Modifier = Modifier, height: Dp = 2.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.25f), cornerRadius = r)
        val f = if (duration > 0) (position().toFloat() / duration).coerceIn(0f, 1f) else 0f
        drawRoundRect(Color.White, Offset.Zero, Size(size.width * f, size.height), r)
    }
}
