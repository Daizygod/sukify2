package ru.nepalimsya.sukify.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.ui.theme.SukifyColors

/**
 * Пляшущий эквалайзер у играющего трека — как в списках Spotify. На паузе
 * столбики замирают на разной высоте, а не пропадают.
 */
@Composable
fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier, size: Dp = 14.dp, color: Color = SukifyColors.Accent) {
    if (playing) {
        val transition = rememberInfiniteTransition(label = "eq")
        // У каждого столбика свой период — так они не шагают строем.
        val a by transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "eq1")
        val b by transition.animateFloat(1f, 0.3f, infiniteRepeatable(tween(360, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "eq2")
        val c by transition.animateFloat(0.4f, 0.95f, infiniteRepeatable(tween(510, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "eq3")
        // Значения читаем в фазе рисования — строка не перекомпонуется каждый кадр.
        Canvas(modifier.size(size)) { drawBars(a, b, c, color) }
    } else {
        Canvas(modifier.size(size)) { drawBars(0.45f, 0.8f, 0.3f, color) }
    }
}

private fun DrawScope.drawBars(a: Float, b: Float, c: Float, color: Color) {
    val gap = size.width * 0.14f
    val w = (size.width - gap * 2) / 3f
    floatArrayOf(a, b, c).forEachIndexed { i, h ->
        val bh = size.height * h
        drawRoundRect(
            color = color,
            topLeft = Offset(i * (w + gap), size.height - bh),
            size = Size(w, bh),
            cornerRadius = CornerRadius(w * 0.25f),
        )
    }
}
