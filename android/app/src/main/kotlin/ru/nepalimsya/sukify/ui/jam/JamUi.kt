package ru.nepalimsya.sukify.ui.jam

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.data.model.UserDto
import ru.nepalimsya.sukify.jam.JamManager
import ru.nepalimsya.sukify.jam.JamUi
import ru.nepalimsya.sukify.ui.components.AvatarImage
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyMotion
import ru.nepalimsya.sukify.ui.theme.SukifyType

@HiltViewModel
class JamViewModel @Inject constructor(private val jam: JamManager) : ViewModel() {
    val ui: StateFlow<JamUi?> = jam.ui

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private fun run(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    fun start() = run { jam.start() }

    fun join(code: String) = run { jam.join(code) }

    fun leave() = run { jam.leave() }
}

/** Открыть шит джема с любого места: «Создать», устройства, полоса над плеером. */
@Stable
class JamController {
    var open by mutableStateOf(false)
}

val LocalJam = staticCompositionLocalOf { JamController() }

val JamBarHeight = 28.dp

/**
 * Зелёная полоса джема над мини-плеером (JamBar.vue): аватар хоста и
 * «Джем пользователя …»; пропала связь — полоса гаснет, но остаётся.
 */
@Composable
fun JamBar(ui: JamUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (ui.connected) SukifyColors.Accent else Color(0xFF147A37), SukifyMotion.colorFade(), label = "jam")
    val host = ui.host
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(24.dp)
            .bounceClick(pressed = 0.98f, onClick = onClick)
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Face(host, 18.dp)
        Spacer(Modifier.width(8.dp))
        // Подпись одна и для хоста, и для гостя — как в оригинале.
        Text(
            "Джем пользователя ${host?.name ?: host?.username ?: "—"}",
            style = SukifyType.LabelBold,
            color = if (ui.connected) Color.Black else Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!ui.connected) {
            Text(" · нет связи", style = SukifyType.Label, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun Face(user: UserDto?, size: androidx.compose.ui.unit.Dp, ring: Boolean = false) {
    val mod = if (ring) Modifier.border(2.dp, SukifyColors.Accent, CircleShape) else Modifier
    if (user?.avatarUrl != null) {
        AvatarImage(user.avatarUrl, size, mod)
    } else {
        Box(mod.size(size).background(SukifyColors.Elevated, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                (user?.name?.firstOrNull() ?: '?').uppercase(),
                color = Color.White,
                style = SukifyType.LabelBold.copy(fontSize = (size.value * 0.5f).sp),
            )
        }
    }
}

/**
 * Джем — «слушаем вместе» (секция в ConnectPanel.vue): в джеме — код,
 * участники и выход; иначе — начать свой или войти по коду.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamSheet(ui: JamUi?, busy: Boolean, onStart: () -> Unit, onJoin: (String) -> Unit, onLeave: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = SukifyColors.CardPressed) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SukifyIcons.Jam, null, tint = SukifyColors.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Джем — слушаем вместе", style = SukifyType.Title, color = SukifyColors.Text)
            }
            Spacer(Modifier.height(16.dp))
            if (ui != null) {
                // Код — крупно: его диктуют друзьям; тап — поделиться.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .bounceClick(pressed = 0.97f) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Заходи в мой джем в Sukify — код ${ui.code}")
                            }
                            context.startActivity(Intent.createChooser(send, "Пригласить в джем"))
                        }
                        .background(FieldColor, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Код джема", style = SukifyType.Label, color = SukifyColors.TextSubdued)
                        Text(ui.code, style = SukifyType.Hero.copy(fontSize = 30.sp, letterSpacing = 4.sp), color = SukifyColors.Text)
                    }
                    Icon(SukifyIcons.Share, "Поделиться кодом", tint = SukifyColors.Text, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.members, key = { it.id }) { m -> Face(m, 40.dp, ring = m.id == ui.host?.id) }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    buildString {
                        append(if (ui.isHost) "Ты хост" else "Хост: ${ui.host?.name ?: "—"}")
                        append(" • участников: ${ui.members.size}")
                        if (!ui.connected) append(" • нет связи с сервером")
                    },
                    style = SukifyType.BodySmall,
                    color = SukifyColors.TextSubdued,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Переключать треки и ставить паузу может любой — у всех играет одно и то же.",
                    style = SukifyType.BodySmall,
                    color = SukifyColors.TextSubdued,
                )
                Spacer(Modifier.height(20.dp))
                SheetButton(if (ui.isHost) "Закончить джем" else "Выйти из джема", primary = false, enabled = !busy, onClick = onLeave)
            } else {
                Text(
                    "Включай музыку, а друзья подключатся по коду — у всех будет играть одно и то же.",
                    style = SukifyType.BodySmall,
                    color = SukifyColors.TextSubdued,
                )
                Spacer(Modifier.height(16.dp))
                SheetButton("Начать джем", primary = true, enabled = !busy, onClick = onStart)
                Spacer(Modifier.height(20.dp))
                Text("Или войди в джем друга", style = SukifyType.BodySmallBold, color = SukifyColors.Text)
                Spacer(Modifier.height(8.dp))
                var code by remember { mutableStateOf("") }
                val submit = { if (code.isNotBlank()) onJoin(code) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                        singleLine = true,
                        textStyle = SukifyType.Title.copy(color = SukifyColors.Text, letterSpacing = 3.sp),
                        cursorBrush = SolidColor(SukifyColors.Accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        decorationBox = { inner ->
                            Box(
                                Modifier
                                    .background(FieldColor, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                            ) {
                                if (code.isEmpty()) Text("Код джема", style = SukifyType.Title, color = SukifyColors.TextMuted)
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    SheetButton("Войти", primary = false, enabled = !busy && code.length >= 4, onClick = submit, modifier = Modifier.width(110.dp))
                }
            }
        }
    }
}

@Composable
private fun SheetButton(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    Box(
        modifier
            .height(48.dp)
            .bounceClick(enabled = enabled, pressed = 0.96f, onClick = onClick)
            .background(
                when {
                    !enabled -> FieldColor
                    primary -> SukifyColors.Accent
                    else -> Color.White
                },
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SukifyType.BodyBold, color = if (enabled) Color.Black else SukifyColors.TextMuted)
    }
}

/** Поля и неактивные кнопки на шите: светлее его фона. */
private val FieldColor = Color.White.copy(alpha = 0.08f)
