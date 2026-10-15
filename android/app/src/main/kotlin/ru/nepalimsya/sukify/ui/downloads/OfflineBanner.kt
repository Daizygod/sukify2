package ru.nepalimsya.sukify.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.ExtraIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

val OfflineBannerHeight = 30.dp

/** «Нет сети — доступно скачанное»: тонкая полоса над мини-плеером. */
@Composable
fun OfflineBanner(offlineMode: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(OfflineBannerHeight)
            .background(SukifyColors.CardPressed)
            .bounceClick(pressed = 0.99f, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ExtraIcons.Offline, null, tint = SukifyColors.TextSubdued, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            if (offlineMode) "Офлайн-режим — играет только скачанное" else "Нет сети — доступно скачанное",
            style = SukifyType.LabelBold,
            color = SukifyColors.TextSubdued,
        )
    }
}
