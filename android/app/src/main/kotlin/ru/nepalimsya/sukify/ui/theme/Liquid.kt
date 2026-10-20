package ru.nepalimsya.sukify.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * «Жидкие» формы — капли, которые тянутся друг к другу и слипаются, как
 * кружки историй в Telegram. Рисуем геометрией (кривые Безье между
 * касательными), а не размытием с порогом: так работает на любом Android и
 * не портит картинки, которые лежат сверху.
 */
object Liquid {

    /**
     * Перемычка между двумя кругами (классический metaball). null — круги
     * слишком далеко друг от друга (капля оторвалась) или один внутри другого.
     * [stickiness] — насколько далеко тянется: перемычка рвётся на расстоянии
     * r1 + r2 × stickiness.
     */
    fun bridge(c1: Offset, r1: Float, c2: Offset, r2: Float, stickiness: Float = 2.5f, spread: Float = 0.5f): Path? {
        val d = hypot(c2.x - c1.x, c2.y - c1.y)
        if (r1 <= 0f || r2 <= 0f || d > r1 + r2 * stickiness || d <= abs(r1 - r2)) return null
        val u1: Float
        val u2: Float
        if (d < r1 + r2) {
            u1 = acos(((r1 * r1 + d * d - r2 * r2) / (2 * r1 * d)).coerceIn(-1f, 1f))
            u2 = acos(((r2 * r2 + d * d - r1 * r1) / (2 * r2 * d)).coerceIn(-1f, 1f))
        } else {
            u1 = 0f
            u2 = 0f
        }
        val between = atan2(c2.y - c1.y, c2.x - c1.x)
        val maxSpread = acos(((r1 - r2) / d).coerceIn(-1f, 1f))
        val pi = PI.toFloat()

        val a1 = between + u1 + (maxSpread - u1) * spread
        val a2 = between - u1 - (maxSpread - u1) * spread
        val a3 = between + pi - u2 - (pi - u2 - maxSpread) * spread
        val a4 = between - pi + u2 + (pi - u2 - maxSpread) * spread
        val p1 = c1 + polar(a1, r1)
        val p2 = c1 + polar(a2, r1)
        val p3 = c2 + polar(a3, r2)
        val p4 = c2 + polar(a4, r2)

        val total = r1 + r2
        val handle = min(spread * 2.4f, hypot(p3.x - p1.x, p3.y - p1.y) / total) * min(1f, d * 2 / total)
        val h1 = p1 + polar(a1 - pi / 2, r1 * handle)
        val h2 = p2 + polar(a2 + pi / 2, r1 * handle)
        val h3 = p3 + polar(a3 + pi / 2, r2 * handle)
        val h4 = p4 + polar(a4 - pi / 2, r2 * handle)

        return Path().apply {
            moveTo(p1.x, p1.y)
            cubicTo(h1.x, h1.y, h3.x, h3.y, p3.x, p3.y)
            lineTo(p4.x, p4.y)
            cubicTo(h4.x, h4.y, h2.x, h2.y, p2.x, p2.y)
            close()
        }
    }

    /**
     * Горлышко между двумя прямоугольниками, стоящими рядом по горизонтали.
     * Граница выходит из кромки по касательной, как мениск: жидкость
     * «стекает» с обложки, к середине сужается в талию и рвётся, когда щель
     * шире [maxGap]. Рисуется под обложками — видна только сама перемычка.
     */
    fun neck(leftEdge: Float, rightEdge: Float, centerY: Float, height: Float, maxGap: Float): Path? {
        val gap = rightEdge - leftEdge
        if (gap <= 0f || gap >= maxGap) return null
        val t = gap / maxGap
        // Устье у кромки и талия посередине: чем шире щель, тем тоньше.
        val mouth = height * 0.3f * (1f - 0.5f * t)
        val waist = height * 0.2f * (1f - t) * (1f - t)
        val mid = (leftEdge + rightEdge) / 2f
        val half = gap / 2f
        val fall = (mouth - waist) * 0.55f // насколько мениск идёт вдоль кромки
        val overlap = height * 0.04f // заходим под обложку, чтобы не было шва
        return Path().apply {
            moveTo(leftEdge - overlap, centerY - mouth)
            lineTo(leftEdge, centerY - mouth)
            cubicTo(leftEdge, centerY - mouth + fall, mid - half * 0.55f, centerY - waist, mid, centerY - waist)
            cubicTo(mid + half * 0.55f, centerY - waist, rightEdge, centerY - mouth + fall, rightEdge, centerY - mouth)
            lineTo(rightEdge + overlap, centerY - mouth)
            lineTo(rightEdge + overlap, centerY + mouth)
            lineTo(rightEdge, centerY + mouth)
            cubicTo(rightEdge, centerY + mouth - fall, mid + half * 0.55f, centerY + waist, mid, centerY + waist)
            cubicTo(mid - half * 0.55f, centerY + waist, leftEdge, centerY + mouth - fall, leftEdge, centerY + mouth)
            lineTo(leftEdge - overlap, centerY + mouth)
            close()
        }
    }

    /**
     * Слеза: круглая голова [head] радиуса [radius] и острый кончик в [tail]
     * — бока идут от кончика по касательным к голове. Для брызг.
     */
    fun teardrop(head: Offset, radius: Float, tail: Offset): Path {
        val length = hypot(tail.x - head.x, tail.y - head.y)
        if (radius <= 0f || length <= radius) {
            return Path().apply { if (radius > 0f) addOval(Rect(head, radius)) }
        }
        val toTail = atan2(tail.y - head.y, tail.x - head.x)
        val spread = acos(radius / length) // угол от оси до точки касания
        val start = toTail + spread
        val sweep = 2 * PI.toFloat() - 2 * spread
        val degrees = 180f / PI.toFloat()
        return Path().apply {
            moveTo(tail.x, tail.y)
            val p = head + polar(start, radius)
            lineTo(p.x, p.y)
            arcTo(Rect(head, radius), start * degrees, sweep * degrees, false)
            close()
        }
    }

    private fun polar(angle: Float, radius: Float) = Offset(cos(angle) * radius, sin(angle) * radius)
}

/**
 * Две капли и перемычка между ними, если они ещё не оторвались. Всё
 * сливается в один контур — полупрозрачный цвет не темнеет на стыках.
 */
fun DrawScope.drawLiquidPair(
    c1: Offset,
    r1: Float,
    c2: Offset,
    r2: Float,
    color: Color,
    stickiness: Float = 2.5f,
) {
    var shape = Path()
    fun union(other: Path) {
        val previous = shape
        shape = Path().apply { op(previous, other, PathOperation.Union) }
    }
    if (r1 > 0f) union(Path().apply { addOval(Rect(c1, r1)) })
    if (r2 > 0f) union(Path().apply { addOval(Rect(c2, r2)) })
    Liquid.bridge(c1, r1, c2, r2, stickiness)?.let(::union)
    drawPath(shape, color)
}

/** Перемычка между соседними обложками; цвет плавно идёт от одной к другой. */
fun DrawScope.drawLiquidNeck(
    leftEdge: Float,
    rightEdge: Float,
    centerY: Float,
    height: Float,
    maxGap: Float,
    leftColor: Color,
    rightColor: Color,
) {
    val path = Liquid.neck(leftEdge, rightEdge, centerY, height, maxGap) ?: return
    drawPath(path, Brush.horizontalGradient(listOf(leftColor, rightColor), startX = leftEdge, endX = rightEdge))
}
