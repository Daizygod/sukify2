package ru.nepalimsya.sukify.ui.player

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.connect.ConnectDevice
import ru.nepalimsya.sukify.ui.components.ThinSlider
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

/**
 * «Подключение к устройству» (MobileDevicesSheet.vue): список устройств
 * аккаунта, активное — зелёным. Тап по другому — отдать воспроизведение
 * туда, по этому телефону — забрать сюда. Пока играет не здесь — громкость
 * того устройства.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(
    devices: List<ConnectDevice>,
    activeId: String?,
    remoteVolume: Float?,
    onPick: (ConnectDevice) -> Unit,
    onVolume: (Float) -> Unit,
    onDismiss: () -> Unit,
    onJam: () -> Unit = {},
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = SukifyColors.CardPressed,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
            Text(
                "Подключение к устройству",
                style = SukifyType.Title,
                color = SukifyColors.Text,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            val active = devices.firstOrNull { it.id == activeId }
            if (active != null && !active.self && remoteVolume != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(SukifyIcons.Volume, "Громкость", tint = SukifyColors.TextSubdued, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    VolumeSlider(remoteVolume, onVolume, Modifier.weight(1f))
                }
            }
            devices.forEach { d ->
                DeviceRow(d, active = d.id == activeId, onClick = { onPick(d) })
            }
            if (devices.size <= 1) {
                Text(
                    "Открой Sukify на другом устройстве под этим же аккаунтом — оно появится здесь.",
                    style = SukifyType.BodySmall,
                    color = SukifyColors.TextSubdued,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .bounceClick(pressed = 0.98f, onClick = onJam)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SukifyIcons.Jam, null, tint = SukifyColors.TextSubdued, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Джем", style = SukifyType.BodyBold, color = SukifyColors.Text)
                    Text("Слушайте вместе с друзьями — у всех одно и то же", style = SukifyType.Label, color = SukifyColors.TextSubdued)
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: ConnectDevice, active: Boolean, onClick: () -> Unit) {
    val tint = if (active) SukifyColors.Accent else SukifyColors.Text
    Row(
        Modifier
            .fillMaxWidth()
            .bounceClick(pressed = 0.98f, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (device.type == "phone") SukifyIcons.Miniplayer else SukifyIcons.Devices,
            null,
            tint = if (active) SukifyColors.Accent else SukifyColors.TextSubdued,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(device.name, style = SukifyType.BodyBold, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                active -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(SukifyIcons.Volume, null, tint = SukifyColors.Accent, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (device.playing || device.self) "Сейчас играет" else "На паузе", style = SukifyType.Label, color = SukifyColors.Accent)
                }
                device.self -> Text("Это устройство", style = SukifyType.Label, color = SukifyColors.TextSubdued, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/**
 * Громкость другого устройства: тянем — уходит не чаще восьми раз в
 * секунду (иначе канал захлебнётся), отпустили — точное значение.
 */
@Composable
private fun VolumeSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    var lastSent by remember { mutableLongStateOf(0L) }
    var latest by remember { mutableFloatStateOf(value) }
    ThinSlider(
        value = value,
        onChange = { v ->
            latest = v
            val now = SystemClock.elapsedRealtime()
            if (now - lastSent > 125) {
                lastSent = now
                onChange(v)
            }
        },
        onFinished = { onChange(latest) },
        modifier = modifier,
    )
}
