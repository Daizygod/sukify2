package ru.nepalimsya.sukify.playback.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Формы кроссфейда — порт frontend/src/lib/curves.js. t — доля перехода 0..1,
 * результат — доля пути от «было» к «стало».
 */
enum class CurveType(val wire: String) {
    Linear("linear"),
    EqualPower("equal_power"),
    Exponential("exponential"),
    Logarithmic("logarithmic"),
    SCurve("s_curve"),
    ;

    fun shape(t: Float, descending: Boolean): Float = when (this) {
        // Равная мощность, как у Spotify: затухание по косинусу, нарастание
        // по синусу — cos² + sin² = 1, провала громкости посередине нет.
        EqualPower -> if (descending) 1f - cos(t * PI.toFloat() / 2f) else sin(t * PI.toFloat() / 2f)
        Exponential -> t * t
        Logarithmic -> sqrt(t)
        SCurve -> t * t * (3f - 2f * t)
        Linear -> t
    }

    /** Значение огибающей от [from] к [to] в момент t (как buildCurve в вебе). */
    fun value(from: Float, to: Float, t: Float): Float = from + (to - from) * shape(t, from > to)

    companion object {
        fun fromWire(value: String?): CurveType = entries.firstOrNull { it.wire == value } ?: EqualPower
    }
}
