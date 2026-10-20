package ru.nepalimsya.sukify.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.Liquid
import ru.nepalimsya.sukify.ui.theme.SukifyColors

/**
 * «Добавить в любимые» — круг с плюсом / зелёная галочка, как в вебе
 * (plusCircleBig / checkCircleBig). Нажатие «подпрыгивает» и отдаётся в руку,
 * а при лайке из кнопки брызгают зелёные капли-слёзы.
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
    val scope = rememberCoroutineScope()
    val color by animateColorAsState(if (liked) SukifyColors.Accent else tint, label = "like")
    val pop = remember { Animatable(1f) }
    val splash = remember { Animatable(1f) } // 1 — брызг нет
    Box(
        modifier = modifier
            .size(size + 16.dp)
            .bounceClick(pressed = 0.85f) {
                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                // Прыгаем и брызгаем только от нажатия «нравится» — не когда лайкнутый
                // трек просто появился на экране или сменился в мини-плеере.
                if (!liked) {
                    scope.launch {
                        splash.snapTo(0f)
                        splash.animateTo(1f, tween(560, easing = LinearOutSlowInEasing))
                    }
                    scope.launch {
                        pop.snapTo(1f)
                        pop.animateTo(1f, keyframes {
                            durationMillis = 320
                            1.25f at 120
                            0.95f at 220
                        })
                    }
                }
                onToggle()
            }
            .semantics { contentDescription = if (liked) "Убрать из любимых" else "Добавить в любимые" }
            .drawBehind {
                val t = splash.value
                if (t >= 1f) return@drawBehind
                val side = this.size.minDimension
                val reach = side * (0.32f + 0.5f * t)
                val r = side * 0.07f * (1f - t)
                repeat(DROPS) { i ->
                    val angle = (i * 2 * PI / DROPS - PI / 2).toFloat()
                    val dir = Offset(cos(angle), sin(angle))
                    val head = center + dir * reach
                    // Слеза: круглая голова летит наружу, острый кончик — к кнопке.
                    val tail = center + dir * (reach - r * 2.6f)
                    drawPath(Liquid.teardrop(head, r, tail), SukifyColors.Accent.copy(alpha = 1f - t * t))
                }
            },
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

private const val DROPS = 7
