package ru.nepalimsya.sukify.ui.player

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Лента мини-плеера и карусели обложек: палец двигает её один к одному (раньше
 * часть движений терялась, и лента застревала на полпути), короткий жест
 * возвращает назад, длинный переключает трек ровно один раз, а после смены
 * трека в центре сразу новый слайд.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class SwipeStripTest {

    @get:Rule
    val rule = createComposeRule()

    private var current by mutableIntStateOf(5)
    private var nextCalls = 0
    private var previousCalls = 0

    private fun setStrip() {
        rule.setContent {
            // 1 px = 1 dp: удобно сравнивать сдвиг с движением пальца.
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.width(WIDTH).testTag("strip")) {
                    SwipeStrip(
                        previous = current - 1,
                        current = current,
                        next = current + 1,
                        itemKey = { it },
                        onNext = {
                            nextCalls++
                            current++
                        },
                        onPrevious = {
                            previousCalls++
                            current--
                        },
                    ) { item ->
                        Box(Modifier.fillMaxWidth().height(40.dp).testTag("slide$item"))
                    }
                }
            }
        }
    }

    private fun left(tag: String): Dp = rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot().left

    @Test
    fun `strip follows the finger one to one`() {
        setStrip()
        var slop = 0f
        rule.onNodeWithTag("strip").performTouchInput {
            slop = viewConfiguration.touchSlop
            down(center)
            // Мелкими шагами, как настоящий палец: 15 × 8 = 120 px.
            repeat(15) { moveBy(Offset(-8f, 0f)) }
        }
        rule.waitForIdle()
        // Сдвиг = путь пальца минус порог касания; ничего не потеряно по дороге.
        assertEquals(-(120f - slop), left("slide5").value, 1.5f)
        assertEquals(WIDTH.value - (120f - slop), left("slide6").value, 1.5f)
        rule.onNodeWithTag("strip").performTouchInput { up() }
    }

    @Test
    fun `short swipe springs back and does not switch`() {
        setStrip()
        rule.onNodeWithTag("strip").performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(-8f, 0f)) } // 40 px — меньше порога в 60
            up()
        }
        rule.waitForIdle()
        assertEquals(0, nextCalls)
        assertEquals(0f, left("slide5").value, 0.5f)
    }

    @Test
    fun `long swipe switches exactly once and the new slide lands in the centre`() {
        setStrip()
        rule.onNodeWithTag("strip").performTouchInput {
            down(center)
            repeat(15) { moveBy(Offset(-8f, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(1, nextCalls)
        assertEquals(6, current)
        // Новый текущий — в центре, прежний ушёл влево на ширину ленты.
        assertEquals(0f, left("slide6").value, 0.5f)
        assertEquals(-WIDTH.value, left("slide5").value, 0.5f)
    }

    @Test
    fun `swipe right goes to the previous track`() {
        setStrip()
        rule.onNodeWithTag("strip").performTouchInput {
            down(center)
            repeat(15) { moveBy(Offset(8f, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(1, previousCalls)
        assertEquals(0, nextCalls)
        assertEquals(4, current)
        assertEquals(0f, left("slide4").value, 0.5f)
    }

    @Test
    fun `track changed from outside recentres without a jump`() {
        setStrip()
        current = 9 // доиграл сам или переключили кнопкой
        rule.waitForIdle()
        assertEquals(0f, left("slide9").value, 0.5f)
        assertTrue(nextCalls == 0 && previousCalls == 0)
    }

    private companion object {
        val WIDTH = 300.dp
    }
}
