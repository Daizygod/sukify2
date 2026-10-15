package ru.nepalimsya.sukify.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors

/**
 * «Добавить в любимые» — круг с плюсом / зелёная галочка, как в вебе
 * (plusCircleBig / checkCircleBig). Нажатие «подпрыгивает» и отдаётся в руку.
 */
@Composable
fun LikeButton(
    liked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: androidx.compose.ui.graphics.Color = SukifyColors.Text,
) {
    val haptics = LocalHapticFeedback.current
    val color by animateColorAsState(if (liked) SukifyColors.Accent else tint, label = "like")
    val pop = remember { Animatable(1f) }
    LaunchedEffect(liked) {
        if (liked) {
            pop.animateTo(1f, keyframes {
                durationMillis = 320
                1.25f at 120
                0.95f at 220
            })
        }
    }
    Box(
        modifier = modifier
            .size(size + 16.dp)
            .bounceClick(pressed = 0.85f) {
                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                onToggle()
            }
            .semantics { contentDescription = if (liked) "Убрать из любимых" else "Добавить в любимые" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (liked) SukifyIcons.CheckCircleBig else SukifyIcons.PlusCircleBig,
            contentDescription = null,
            tint = color,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
        )
    }
}
