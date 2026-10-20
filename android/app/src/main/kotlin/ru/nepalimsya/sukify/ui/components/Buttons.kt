package ru.nepalimsya.sukify.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyMotion
import ru.nepalimsya.sukify.ui.theme.SukifyType

/**
 * Нажатие «проседает» и пружинит обратно — тактильный отклик без ряби.
 * Как капля: под пальцем кнопка чуть сплющивается (по высоте сильнее, чем
 * по ширине), а отпущенная отыгрывает ширину и высоту на разных пружинах —
 * не в такт, поэтому коротко «желейно» колышется. Масштаб — в graphicsLayer,
 * то есть без перерасчёта раскладки.
 */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.95f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val dent = 1f - pressed
    val x by animateFloatAsState(
        if (isPressed) 1f - dent * 0.6f else 1f,
        if (isPressed) SukifyMotion.standard() else spring(dampingRatio = 0.42f, stiffness = 620f),
        label = "pressX",
    )
    val y by animateFloatAsState(
        if (isPressed) 1f - dent * 1.4f else 1f,
        if (isPressed) SukifyMotion.standard() else spring(dampingRatio = 0.42f, stiffness = 380f),
        label = "pressY",
    )
    graphicsLayer {
        scaleX = x
        scaleY = y
    }
}

/** Нажатие без ряби, с «проседанием» и лёгкой отдачей в руку. */
fun Modifier.bounceClick(
    enabled: Boolean = true,
    haptic: Boolean = false,
    pressed: Float = 0.95f,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    this
        .pressScale(interaction, pressed)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
            if (haptic) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        }
}

/** Зелёная кнопка-«таблетка»: главное действие экрана. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .bounceClick(enabled = enabled && !loading, onClick = onClick)
            .background(if (enabled) SukifyColors.Accent else SukifyColors.Accent.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 32.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
        } else {
            Text(text, style = SukifyType.BodyBold, color = Color.Black)
        }
    }
}

/** Кнопка с обводкой: второстепенное действие («Изменить», «Подписаться»). */
@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 36.dp)
            .bounceClick(enabled = enabled, onClick = onClick)
            .border(1.dp, SukifyColors.TextMuted, CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SukifyType.BodySmallBold, color = SukifyColors.Text)
    }
}
