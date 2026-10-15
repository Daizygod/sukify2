package ru.nepalimsya.sukify.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Движение интерфейса: пружины вместо линейных кривых — анимация
 * подхватывается с текущей скорости пальца и не дёргается при прерывании.
 */
object SukifyMotion {
    /** Обычный отклик: нажатия, появление элементов. */
    fun <T> standard(): FiniteAnimationSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f)

    /** Крупные перемещения: шиты, «Сейчас играет», карусель обложек. */
    fun <T> sheet(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.86f, stiffness = 420f)

    /** Лёгкий пружинящий отклик: лайк, переключатели. */
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.55f, stiffness = 700f)

    /** Перекраска фона под новую обложку. */
    fun <T> colorFade(): FiniteAnimationSpec<T> = tween(durationMillis = 650, easing = FastOutSlowInEasing)

    const val NAV_MS = 220
}
