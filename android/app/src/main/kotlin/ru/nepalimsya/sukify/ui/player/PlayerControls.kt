package ru.nepalimsya.sukify.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.playback.queue.RepeatMode
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyMotion
import ru.nepalimsya.sukify.ui.theme.SukifyType

/** Перемешать · ⏮ · ▶ · ⏭ · Повтор — как в «Сейчас играет» веба. */
@Composable
fun PlayerControls(
    playing: Boolean,
    buffering: Boolean,
    shuffle: Boolean,
    repeat: RepeatMode,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onPlay: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleControl(SukifyIcons.Shuffle, active = shuffle, description = "Перемешать", onClick = onShuffle)
        ControlButton(SukifyIcons.Prev, "Предыдущий", size = 30, onClick = onPrevious)
        Box(
            Modifier
                .size(64.dp)
                .bounceClick(pressed = 0.93f) {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onPlay()
                }
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = playing,
                transitionSpec = {
                    (fadeIn(tween(120)) + scaleIn(SukifyMotion.bouncy(), initialScale = 0.7f)) togetherWith
                        (fadeOut(tween(90)) + scaleOut(targetScale = 0.7f))
                },
                label = "play",
            ) { isPlaying ->
                Icon(
                    if (isPlaying) SukifyIcons.PauseBig else SukifyIcons.PlayBig,
                    contentDescription = if (isPlaying) "Пауза" else "Слушать",
                    tint = Color.Black,
                    modifier = Modifier.size(30.dp),
                )
            }
            if (buffering && playing) {
                CircularProgressIndicator(Modifier.size(64.dp), color = Color.Black.copy(alpha = 0.25f), strokeWidth = 2.dp)
            }
        }
        ControlButton(SukifyIcons.Next, "Следующий", size = 30, onClick = onNext)
        Box(contentAlignment = Alignment.Center) {
            ToggleControl(SukifyIcons.Repeat, active = repeat != RepeatMode.Off, description = "Повтор", onClick = onRepeat)
            if (repeat == RepeatMode.One) {
                Text(
                    "1",
                    style = SukifyType.Tiny.copy(fontSize = SukifyType.Tiny.fontSize * 0.9f),
                    color = SukifyColors.Accent,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 6.dp),
                )
            }
        }
    }
}

@Composable
fun ControlButton(icon: ImageVector, description: String, size: Int, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).bounceClick(pressed = 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size.dp))
    }
}

/** Переключатель с зелёной точкой снизу, когда включён (как в Spotify). */
@Composable
fun ToggleControl(icon: ImageVector, active: Boolean, description: String, onClick: () -> Unit) {
    val color by animateColorAsState(if (active) SukifyColors.Accent else SukifyColors.TextSubdued, label = "toggle")
    Box(
        Modifier
            .size(48.dp)
            .bounceClick(pressed = 0.85f, haptic = true, onClick = onClick)
            .drawBehind {
                if (active) drawCircle(color, radius = 2.dp.toPx(), center = Offset(size.width / 2, size.height - 6.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = color, modifier = Modifier.size(22.dp))
    }
}
