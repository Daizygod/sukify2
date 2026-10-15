package ru.nepalimsya.sukify.ui.shell

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

/** Высота панели без системной полосы навигации. */
val BottomBarHeight = 58.dp

/**
 * Нижняя панель как в приложении Spotify (и MobileNav.vue): полупрозрачный
 * градиент — контент виден сквозь затемнение.
 */
@Composable
fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit, onCreate: () -> Unit, modifier: Modifier = Modifier) {
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
            .height(BottomBarHeight),
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
