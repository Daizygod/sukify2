package ru.nepalimsya.sukify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

/** Заголовок раздела настроек. */
@Composable
fun SettingsSection(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 6.dp)) {
        Text(title, style = SukifyType.Title, color = SukifyColors.Text)
        subtitle?.let { Text(it, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** Строка-переключатель: тап по всей строке тоже переключает. */
@Composable
fun SettingSwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .bounceClick(enabled = enabled, pressed = 0.99f) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SukifyType.Body, color = if (enabled) SukifyColors.Text else SukifyColors.TextMuted)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = SukifyType.Caption, color = SukifyColors.TextSubdued)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = SukifyColors.Accent, checkedThumbColor = Color.White),
        )
    }
}

/** Строка с текстом и действием справа (кнопка, значение) или переходом. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = { if (onClick != null) Icon(SukifyIcons.ChevronRight, null, tint = SukifyColors.TextSubdued, modifier = Modifier.size(16.dp)) },
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.bounceClick(pressed = 0.99f, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SukifyType.Body, color = SukifyColors.Text)
            subtitle?.let { Text(it, style = SukifyType.Caption, color = SukifyColors.TextSubdued) }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** Небольшая кнопка-пилюля для строк настроек. */
@Composable
fun PillButton(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        style = SukifyType.BodySmallBold,
        color = when {
            !enabled -> SukifyColors.TextMuted
            primary -> Color.Black
            else -> SukifyColors.Text
        },
        modifier = Modifier
            .bounceClick(enabled = enabled, pressed = 0.94f, onClick = onClick)
            .background(
                when {
                    !enabled -> SukifyColors.Chip
                    primary -> SukifyColors.Accent
                    else -> SukifyColors.Chip
                },
                CircleShape,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
