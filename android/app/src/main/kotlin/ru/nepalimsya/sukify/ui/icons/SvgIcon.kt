package ru.nepalimsya.sukify.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * SVG-иконка веба → ImageVector. viewBox бывает со смещением («-72 -69.6 …»
 * у иконки медиатеки) — его отрабатываем сдвигом группы.
 * Цвет задаёт Icon(tint = …), здесь заливка просто непрозрачная.
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
            pathData = addPathNodes(normalizePath(pathData)),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.NonZero,
        )
        .clearGroup()
        .build()
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
