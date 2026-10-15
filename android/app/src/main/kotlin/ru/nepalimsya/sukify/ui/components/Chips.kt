package ru.nepalimsya.sukify.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

/** Чип-фильтр как .chip в вебе: белый — выбран, серый — нет. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) SukifyColors.Text else SukifyColors.Chip, label = "chip")
    val fg by animateColorAsState(if (selected) Color.Black else SukifyColors.Text, label = "chipText")
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 32.dp)
            .bounceClick(pressed = 0.94f, onClick = onClick)
            .background(bg, CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SukifyType.BodySmall, color = fg, maxLines = 1)
    }
}

/**
 * Ряд чипов-фильтров. Выбранный фильтр (как в медиатеке Spotify) показывает
 * слева крестик «сбросить», остальные чипы прячутся.
 */
@Composable
fun <T> FilterChips(
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    collapsible: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    val visible = if (collapsible && selected != null) options.filter { it.first == selected } else options
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (collapsible && selected != null) {
            item(key = "clear") {
                Box(
                    Modifier
                        .size(32.dp)
                        .bounceClick(pressed = 0.9f) { onSelect(null) }
                        .background(SukifyColors.Chip, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(SukifyIcons.Plus, "Сбросить", tint = SukifyColors.Text, modifier = Modifier.size(14.dp).rotate(45f))
                }
            }
        }
        items(visible, key = { it.first.toString() }) { (value, label) ->
            Chip(
                text = label,
                selected = value == selected,
                onClick = { onSelect(if (collapsible && value == selected) null else value) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** Небольшой ряд с иконкой и подписью (сортировка в медиатеке/дискографии). */
@Composable
fun IconLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.bounceClick(pressed = 0.96f, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = SukifyColors.Text, modifier = Modifier.size(16.dp))
        Text(text, style = SukifyType.BodySmallBold, color = SukifyColors.Text)
    }
}
