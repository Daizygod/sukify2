package ru.nepalimsya.sukify.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import java.util.Random

/**
 * «Объём» для плоских заливок — то же, что делает веб (и Spotify на шапке
 * главной): зерно поверх цветных градиентов и мягкий свет сверху-слева.
 */
object Depth {
    /**
     * Зерно: тайл случайной серой ряби с прозрачностью ~4%, повторяется по
     * всей площади. Один пиксель шума — два пикселя экрана, иначе на плотных
     * экранах телефонов рябь мельче, чем видит глаз.
     */
    val grain: Brush by lazy {
        val side = 96
        val rnd = Random(7)
        val pixels = IntArray(side * side) {
            val v = rnd.nextInt(256)
            android.graphics.Color.argb(10, v, v, v)
        }
        val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(bitmap, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        shader.setLocalMatrix(Matrix().apply { setScale(2f, 2f) })
        ShaderBrush(shader)
    }

    /** Блик сверху-слева: плашка цвета обложки выглядит выпуклой, а не плоской. */
    val topLight: Brush = object : ShaderBrush() {
        override fun createShader(size: Size): Shader = RadialGradientShader(
            center = Offset(size.width * 0.15f, 0f),
            radius = size.maxDimension * 0.9f,
            colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
        )
    }

    /** Глубина к низу карточки. */
    val bottomShade: Brush = Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.3f))

    /** Серая карточка с объёмом: светлее сверху, к низу темнее. */
    val card: Brush = Brush.verticalGradient(listOf(Color(0xFF262626), Color(0xFF1B1B1B)))
}
