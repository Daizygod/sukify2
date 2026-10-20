package ru.nepalimsya.sukify.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.ui.theme.drawLiquidPair

/** Высота панели без системной полосы навигации. */
val BottomBarHeight = 58.dp

/**
 * Нижняя панель как в приложении Spotify (и MobileNav.vue): полупрозрачный
 * градиент — контент виден сквозь затемнение. Под выбранной вкладкой —
 * мягкая капля: при переключении она перетекает к новой вкладке, вытягивая
 * за собой хвост, и хвост догоняет её, пока не сольётся обратно.
 */
@Composable
fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    val items = Tab.entries.size + 1 // + «Создать»
    val index = Tab.entries.indexOf(selected).toFloat()
    // Голова капли — быстрая и упругая, хвост — ленивый.
    val head = remember { Animatable(index) }
    val tail = remember { Animatable(index) }
    LaunchedEffect(index) {
        launch { head.animateTo(index, spring(dampingRatio = 0.62f, stiffness = 520f)) }
        launch { tail.animateTo(index, spring(dampingRatio = 0.9f, stiffness = 140f)) }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.34f to Color.Black.copy(alpha = 0.62f),
                    1f to Color.Black.copy(alpha = 0.92f),
                ),
            )
            .navigationBarsPadding()
            .height(BottomBarHeight)
            .drawBehind {
                val slot = size.width / items
                val radius = 17.dp.toPx() // от верха панели до подписи
                val y = 8.dp.toPx() + 12.dp.toPx() // центр иконки
                val stretch = (abs(head.value - tail.value)).coerceAtMost(1f)
                drawLiquidPair(
                    c1 = Offset((head.value + 0.5f) * slot, y),
                    r1 = radius * (1f - 0.12f * stretch),
                    c2 = Offset((tail.value + 0.5f) * slot, y),
                    r2 = radius * (1f - 0.6f * stretch),
                    color = Color.White.copy(alpha = 0.085f),
                    stickiness = 3.2f,
                )
            },
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { tab ->
            BarItem(
                label = tab.label,
                icon = if (tab == selected) tab.selectedIcon else tab.icon,
                active = tab == selected,
                onClick = { onSelect(tab) },
            )
        }
        BarItem(label = "Создать", icon = SukifyIcons.Plus, active = false, onClick = onCreate)
    }
}

@Composable
private fun RowScope.BarItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(if (active) SukifyColors.Text else SukifyColors.TextSubdued, label = "tab")
    Column(
        modifier = Modifier
            .weight(1f)
            .bounceClick(pressed = 0.9f, onClick = onClick)
            .semantics {
                role = Role.Tab
                this.selected = active
            }
            .padding(top = 8.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(5.dp))
        Text(label, style = SukifyType.Label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
