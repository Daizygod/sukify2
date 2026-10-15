package ru.nepalimsya.sukify.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.data.model.AppReleaseDto
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyMotion
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.update.UpdateState
import ru.nepalimsya.sukify.util.Format

/** «Доступна версия X»: заметки к выпуску, «Обновить» с прогрессом загрузки, «Позже». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    state: UpdateState,
    currentVersion: String,
    onUpdate: (AppReleaseDto) -> Unit,
    onLater: (AppReleaseDto) -> Unit,
    onDismiss: () -> Unit,
) {
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Installing -> state.release
        is UpdateState.Failed -> state.release
        else -> null
    } ?: return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = SukifyColors.CardPressed) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("Доступна версия ${release.versionName}", style = SukifyType.Title, color = SukifyColors.Text)
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("У тебя $currentVersion")
                    release.size?.let { append(" · обновление ${Format.bytes(it)}") }
                },
                style = SukifyType.BodySmall,
                color = SukifyColors.TextSubdued,
            )
            release.notes?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(14.dp))
                Text(it.trim(), style = SukifyType.BodySmall, color = SukifyColors.Text)
            }
            Spacer(Modifier.height(20.dp))
            when (state) {
                is UpdateState.Downloading -> {
                    val progress by animateFloatAsState(state.progress, SukifyMotion.standard(), label = "apk")
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
                        Box(Modifier.fillMaxWidth(progress).height(6.dp).background(SukifyColors.Accent, CircleShape))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Скачиваем… ${(state.progress * 100).toInt()}%", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
                }
                is UpdateState.Installing -> Text("Подтверди установку в системном окне", style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
                else -> {
                    if (state is UpdateState.Failed) {
                        Text(state.message, style = SukifyType.BodySmall, color = Color(0xFFF15E6C))
                        Spacer(Modifier.height(10.dp))
                    }
                    SheetButton("Обновить", primary = true) { onUpdate(release) }
                    Spacer(Modifier.height(8.dp))
                    SheetButton("Позже", primary = false) { onLater(release) }
                }
            }
        }
    }
}

@Composable
private fun SheetButton(text: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .bounceClick(pressed = 0.96f, onClick = onClick)
            .background(if (primary) SukifyColors.Accent else Color.Transparent, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SukifyType.BodyBold, color = if (primary) Color.Black else SukifyColors.Text)
    }
}
