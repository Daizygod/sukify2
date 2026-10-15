package ru.nepalimsya.sukify.ui.shell

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.menus.NameDialog
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import javax.inject.Inject

@HiltViewModel
class CreateViewModel @Inject constructor(private val library: LibraryState) : ViewModel() {
    val nextNumber: Int get() = library.playlists.value.count { it.isOwner } + 1

    fun create(title: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch { library.createPlaylist(title)?.let { onCreated(it.id) } }
    }
}

/** «Создать» из нижней панели — как одноимённый шит в приложении Spotify. */
@Composable
fun CreateSheet(
    onDismiss: () -> Unit,
    onCreatePlaylist: (Long) -> Unit,
    onTransition: () -> Unit,
    onJam: () -> Unit,
    vm: CreateViewModel = hiltViewModel(),
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var naming by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = SukifyColors.CardPressed,
        dragHandle = null,
    ) {
        Column(Modifier.navigationBarsPadding().padding(vertical = 12.dp)) {
            CreateOption(SukifyIcons.Plus, "Плейлист", "Собери плейлист из треков") {
                naming = "Мой плейлист №${vm.nextNumber}"
            }
            CreateOption(SukifyIcons.Friends, "Совместный плейлист", "Собирайте плейлист вместе с друзьями") {
                naming = "Совместный плейлист №${vm.nextNumber}"
            }
            CreateOption(SukifyIcons.Jam, "Джем", "Слушайте вместе в реальном времени") {
                onDismiss()
                onJam()
            }
            CreateOption(SukifyIcons.Sparkle, "Переход", "Сведи два трека в свой микс") {
                onDismiss()
                onTransition()
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    naming?.let { initial ->
        NameDialog(
            title = "Новый плейлист",
            initial = initial,
            confirm = "Создать",
            onConfirm = { name ->
                naming = null
                vm.create(name, onCreatePlaylist)
            },
            onDismiss = { naming = null },
        )
    }
}

@Composable
private fun CreateOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(pressed = 0.98f, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).background(SukifyColors.Chip, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = SukifyColors.TextSubdued, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = SukifyType.BodyBold, color = SukifyColors.Text)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued)
        }
    }
}
