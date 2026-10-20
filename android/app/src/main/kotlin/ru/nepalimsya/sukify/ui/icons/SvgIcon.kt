package ru.nepalimsya.sukify.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * SVG-иконка веба → ImageVector. viewBox бывает со смещением («-72 -69.6 …»
 * у иконки медиатеки) — его отрабатываем сдвигом группы.
 * Цвет задаёт Icon(tint = …), здесь заливка просто непрозрачная.
 *
 * Узлы переводим в абсолютные координаты: на части телефонов нативный
 * Path.rMoveTo после close() отсчитывает от последней точки контура, а не от
 * его начала (как требует SVG), и «zm0-4.5» уводит следующий контур вбок —
 * полоски очереди превращаются в треугольники, у «Устройств» заливается
 * экран. Robolectric считает правильно, поэтому тесты этого не видели.
 */
internal fun svgIcon(name: String, viewBox: String, pathData: String): ImageVector {
    val (minX, minY, width, height) = viewBox.trim().split(Regex("\\s+")).map { it.toFloat() }
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = width,
        viewportHeight = height,
    )
        .addGroup(translationX = -minX, translationY = -minY)
        .addPath(
            pathData = absolutePathNodes(addPathNodes(normalizePath(pathData))),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.NonZero,
        )
        .clearGroup()
        .build()
}

/**
 * Те же узлы, но без относительных команд: платформе остаются только moveTo,
 * lineTo, cubicTo и т. п. по абсолютным точкам. После Z текущая точка —
 * начало контура (правило SVG), его и держим; если следом не moveTo, ставим
 * его сами — тоже не полагаясь на платформу.
 */
internal fun absolutePathNodes(nodes: List<PathNode>): List<PathNode> {
    var x = 0f
    var y = 0f
    var startX = 0f
    var startY = 0f
    var closed = false
    val result = ArrayList<PathNode>(nodes.size + 4)
    for (node in nodes) {
        if (closed && node !is PathNode.MoveTo && node !is PathNode.RelativeMoveTo && node !is PathNode.Close) {
            result += PathNode.MoveTo(startX, startY)
        }
        closed = node is PathNode.Close
        val out: PathNode = when (node) {
            is PathNode.Close -> node
            is PathNode.MoveTo -> node
            is PathNode.RelativeMoveTo -> PathNode.MoveTo(x + node.dx, y + node.dy)
            is PathNode.LineTo -> node
            is PathNode.RelativeLineTo -> PathNode.LineTo(x + node.dx, y + node.dy)
            is PathNode.HorizontalTo -> PathNode.LineTo(node.x, y)
            is PathNode.RelativeHorizontalTo -> PathNode.LineTo(x + node.dx, y)
            is PathNode.VerticalTo -> PathNode.LineTo(x, node.y)
            is PathNode.RelativeVerticalTo -> PathNode.LineTo(x, y + node.dy)
            is PathNode.CurveTo -> node
            is PathNode.RelativeCurveTo ->
                PathNode.CurveTo(x + node.dx1, y + node.dy1, x + node.dx2, y + node.dy2, x + node.dx3, y + node.dy3)
            is PathNode.ReflectiveCurveTo -> node
            is PathNode.RelativeReflectiveCurveTo ->
                PathNode.ReflectiveCurveTo(x + node.dx1, y + node.dy1, x + node.dx2, y + node.dy2)
            is PathNode.QuadTo -> node
            is PathNode.RelativeQuadTo -> PathNode.QuadTo(x + node.dx1, y + node.dy1, x + node.dx2, y + node.dy2)
            is PathNode.ReflectiveQuadTo -> node
            is PathNode.RelativeReflectiveQuadTo -> PathNode.ReflectiveQuadTo(x + node.dx, y + node.dy)
            is PathNode.ArcTo -> node
            is PathNode.RelativeArcTo -> PathNode.ArcTo(
                node.horizontalEllipseRadius,
                node.verticalEllipseRadius,
                node.theta,
                node.isMoreThanHalf,
                node.isPositiveArc,
                x + node.arcStartDx,
                y + node.arcStartDy,
            )
        }
        when (out) {
            is PathNode.Close -> {
                x = startX
                y = startY
            }
            is PathNode.MoveTo -> {
                x = out.x
                y = out.y
                startX = x
                startY = y
            }
            is PathNode.LineTo -> {
                x = out.x
                y = out.y
            }
            is PathNode.CurveTo -> {
                x = out.x3
                y = out.y3
            }
            is PathNode.ReflectiveCurveTo -> {
                x = out.x2
                y = out.y2
            }
            is PathNode.QuadTo -> {
                x = out.x2
                y = out.y2
            }
            is PathNode.ReflectiveQuadTo -> {
                x = out.x
                y = out.y
            }
            is PathNode.ArcTo -> {
                x = out.arcStartX
                y = out.arcStartY
            }
            else -> Unit
        }
        result += out
    }
    return result
}

/**
 * Разворачивает «сжатую» запись SVG-пути в явную, с пробелами между числами.
 *
 * Минификаторы пишут флаги дуги слитно с координатой: «a4.3 4.3 0 0012.2.8»
 * — это флаги 0 и 0, затем x=12.2 и y=.8. Парсер Compose читает «0012.2» как
 * одно число, и иконка (сердце, галочка в круге) рисуется обломком. Флаги
 * дуги (4-й и 5-й аргументы) всегда один символ — по этому и режем.
 */
internal fun normalizePath(d: String): String {
    val out = StringBuilder(d.length + d.length / 4)
    var i = 0
    var command = ' '
    var arg = 0

    fun isSeparator(c: Char) = c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r'

    fun readNumber(): String {
        val start = i
        if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
        var dot = false
        var exp = false
        while (i < d.length) {
            val c = d[i]
            when {
                c.isDigit() -> i++
                c == '.' && !dot && !exp -> {
                    dot = true
                    i++
                }
                (c == 'e' || c == 'E') && !exp -> {
                    exp = true
                    i++
                    if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
                }
                else -> break
            }
        }
        return d.substring(start, i)
    }

    while (true) {
        while (i < d.length && isSeparator(d[i])) i++
        if (i >= d.length) break
        val c = d[i]
        if (c.isLetter() && c != 'e' && c != 'E') {
            command = c
            arg = 0
            out.append(c).append(' ')
            i++
            continue
        }
        val arc = command == 'a' || command == 'A'
        val token = if (arc && (arg % 7 == 3 || arg % 7 == 4)) {
            d[i++].toString()
        } else {
            readNumber()
        }
        if (token.isEmpty()) {
            // Непонятный символ — пропускаем, чтобы не зациклиться.
            i++
            continue
        }
        out.append(token).append(' ')
        arg++
    }
    return out.toString().trimEnd()
}
