package ru.nepalimsya.sukify.ui.mix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.data.model.TransitionSummaryDto
import ru.nepalimsya.sukify.playback.mix.MixShapes
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

/**
 * Чип перехода между строками в режиме микса (MixChip.vue, dense): стоит под
 * строкой вровень с названием и подписан пресетом пары; без своего перехода —
 * «Авто», он и играет. Нажатие открывает редактор пары.
 */
@Composable
fun MixChip(info: TransitionSummaryDto?, onClick: () -> Unit) {
    val preset = info?.preset ?: "auto"
    val title = if (info != null) MixShapes.PRESETS[preset]?.title ?: "Свой вариант" else MixShapes.PRESETS.getValue("auto").title
    Box(Modifier.fillMaxWidth().padding(start = 76.dp, top = 2.dp, bottom = 2.dp)) {
        Row(
            Modifier
                .bounceClick(pressed = 0.94f, onClick = onClick)
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                .padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (preset == "custom") SukifyIcons.Edit else SukifyIcons.Sparkle,
                null,
                tint = if (preset == "custom") SukifyColors.Accent else SukifyColors.Text,
                modifier = Modifier.size(11.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(title, style = SukifyType.LabelBold, color = SukifyColors.Text)
            val likes = info?.likesCount ?: 0
            if (likes > 0) {
                Spacer(Modifier.width(6.dp))
                Icon(SukifyIcons.HeartFill, null, tint = SukifyColors.TextSubdued, modifier = Modifier.size(9.dp))
                Spacer(Modifier.width(3.dp))
                Text("$likes", style = SukifyType.LabelBold, color = SukifyColors.TextSubdued)
            }
        }
    }
}
