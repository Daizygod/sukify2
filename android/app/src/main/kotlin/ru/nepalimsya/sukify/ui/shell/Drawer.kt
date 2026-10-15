package ru.nepalimsya.sukify.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.nepalimsya.sukify.data.model.UserDto
import ru.nepalimsya.sukify.ui.components.AvatarImage
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

/** Боковое меню профиля (аватар в шапке вкладок), как в приложении Spotify. */
@Stable
class DrawerController(private val onOpen: () -> Unit) {
    fun open() = onOpen()
}

val LocalDrawer = staticCompositionLocalOf { DrawerController {} }

/** Аватар пользователя: фото или первая буква на синем (home__ava в вебе). */
@Composable
fun UserAvatar(user: UserDto?, size: Dp, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick != null) Modifier.bounceClick(pressed = 0.9f, onClick = onClick) else Modifier
    if (user?.avatarUrl != null) {
        AvatarImage(user.avatarUrl, size = size, modifier = modifier)
    } else {
        Box(
            modifier.size(size).background(Color(0xFF509BF5), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (user?.name ?: "?").take(1).uppercase(),
                style = SukifyType.BodyBold,
                color = Color.Black,
            )
        }
    }
}

/** Пункт бокового меню. */
data class DrawerEntry(val icon: ImageVector, val label: String, val dot: Boolean = false, val onClick: () -> Unit)

@Composable
fun ProfileDrawer(user: UserDto?, entries: List<DrawerEntry>, onProfile: () -> Unit) {
    ModalDrawerSheet(
        drawerContainerColor = SukifyColors.Card,
        drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
        modifier = Modifier.width(320.dp).fillMaxHeight(),
    ) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .bounceClick(pressed = 0.98f, onClick = onProfile)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(user, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(user?.name.orEmpty(), style = SukifyType.Title, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Открыть профиль", style = SukifyType.Caption, color = SukifyColors.TextSubdued)
                }
            }
            HorizontalDivider(color = SukifyColors.Divider)
            Spacer(Modifier.height(8.dp))
            entries.forEach { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .bounceClick(pressed = 0.98f, onClick = entry.onClick)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Icon(entry.icon, null, tint = SukifyColors.Text, modifier = Modifier.size(24.dp))
                        if (entry.dot) {
                            Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(SukifyColors.Accent, CircleShape))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(entry.label, style = SukifyType.Body, color = SukifyColors.Text)
                }
            }
        }
    }
}

