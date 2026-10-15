package ru.nepalimsya.sukify.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.model.PendingDeviceDto
import ru.nepalimsya.sukify.ui.components.PageScaffold
import ru.nepalimsya.sukify.ui.components.Toasts
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.shell.LinkDeviceRoute
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

data class LinkUi(
    val code: String = "",
    val step: Step = Step.Enter,
    val device: PendingDeviceDto? = null,
    val error: String? = null,
) {
    enum class Step { Enter, Checking, Found, Approved }
}

@HiltViewModel
class LinkDeviceViewModel @Inject constructor(savedState: SavedStateHandle, private val api: SukifyApi) : ViewModel() {
    private val _ui = MutableStateFlow(LinkUi(code = normalize(savedState.toRoute<LinkDeviceRoute>().code.orEmpty())))
    val ui: StateFlow<LinkUi> = _ui.asStateFlow()

    init {
        // Код приехал из ссылки компаньона — сразу проверяем.
        if (_ui.value.code.length >= 9) lookup()
    }

    fun setCode(raw: String) = _ui.update { it.copy(code = normalize(raw), error = null) }

    fun lookup() {
        val code = _ui.value.code
        if (code.length < 9) return
        _ui.update { it.copy(step = LinkUi.Step.Checking, error = null) }
        viewModelScope.launch {
            apiCall { api.pendingDevice(code).data }
                .onSuccess { d -> _ui.update { it.copy(step = LinkUi.Step.Found, device = d) } }
                .onFailure { _ui.update { it.copy(step = LinkUi.Step.Enter, error = "Код не найден или истёк. Запроси новый в приложении.") } }
        }
    }

    fun approve() {
        val code = _ui.value.code
        viewModelScope.launch {
            apiCall { api.approveDevice(buildJsonObject { put("user_code", code) }) }
                .onSuccess {
                    _ui.update { it.copy(step = LinkUi.Step.Approved) }
                    Toasts.show("Приложение подключено")
                }
                .onFailure { _ui.update { it.copy(step = LinkUi.Step.Enter, error = "Не удалось подтвердить — код мог истечь.") } }
        }
    }

    private companion object {
        /** XXXX-XXXX: только буквы и цифры, дефис — сами. */
        fun normalize(raw: String): String {
            val clean = raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(8)
            return if (clean.length > 4) "${clean.take(4)}-${clean.drop(4)}" else clean
        }
    }
}

/** «Подключение приложения» — привязать Sukify для компьютера (статус в Discord). */
@Composable
fun LinkDeviceScreen(vm: LinkDeviceViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current

    PageScaffold("Подключить компьютер") {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(SukifyColors.Card, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🎧", fontSize = 40.sp)
                Spacer(Modifier.height(12.dp))
                AnimatedContent(ui.step, label = "link") { step ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (step) {
                            LinkUi.Step.Approved -> {
                                Title("Готово")
                                Lead("Приложение подключено к твоему аккаунту. Можешь вернуться в него — статус в Discord появится, как только заиграет музыка.")
                                Spacer(Modifier.height(6.dp))
                                PrimaryButton("Закрыть", enabled = true) { navigator.back() }
                            }
                            LinkUi.Step.Found -> {
                                Title("Подключить приложение?")
                                Lead("Оно сможет читать, что играет, и показывать это в статусе Discord.")
                                Column(
                                    Modifier.fillMaxWidth().background(SukifyColors.Chip, RoundedCornerShape(8.dp)).padding(14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(ui.device?.name.orEmpty(), style = SukifyType.BodyBold, color = SukifyColors.Text)
                                    ui.device?.platform?.let { Text(it, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued) }
                                }
                                CodeText(ui.code)
                                Text(
                                    "Подтверждай, только если этот код прямо сейчас показывает приложение на твоём компьютере.",
                                    style = SukifyType.BodySmall,
                                    color = Color(0xFFFFA42B),
                                    textAlign = TextAlign.Center,
                                )
                                PrimaryButton("Подключить", enabled = true, onClick = vm::approve)
                                Text("Отмена", style = SukifyType.BodyBold, color = SukifyColors.TextSubdued, modifier = Modifier.bounceClick { navigator.back() }.padding(8.dp))
                            }
                            else -> {
                                Title("Подключение приложения")
                                Lead("Введи код, который показывает Sukify на компьютере.")
                                BasicTextField(
                                    value = ui.code,
                                    onValueChange = vm::setCode,
                                    singleLine = true,
                                    textStyle = SukifyType.Title.copy(color = SukifyColors.Text, fontSize = 26.sp, letterSpacing = 3.sp, textAlign = TextAlign.Center),
                                    cursorBrush = SolidColor(SukifyColors.Accent),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { vm.lookup() }),
                                    decorationBox = { inner ->
                                        Box(
                                            Modifier.fillMaxWidth().background(SukifyColors.Chip, RoundedCornerShape(8.dp)).padding(vertical = 14.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (ui.code.isEmpty()) {
                                                Text("XXXX-XXXX", style = SukifyType.Title.copy(fontSize = 26.sp, letterSpacing = 3.sp), color = SukifyColors.TextMuted)
                                            }
                                            inner()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                ui.error?.let { Text(it, style = SukifyType.BodySmall, color = Color(0xFFF15E6C), textAlign = TextAlign.Center) }
                                PrimaryButton(
                                    if (step == LinkUi.Step.Checking) "Проверяем…" else "Продолжить",
                                    enabled = ui.code.length >= 9 && step != LinkUi.Step.Checking,
                                    onClick = vm::lookup,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = SukifyType.PageTitle, color = SukifyColors.Text, textAlign = TextAlign.Center)

@Composable
private fun Lead(text: String) = Text(text, style = SukifyType.BodySmall, color = SukifyColors.TextSubdued, textAlign = TextAlign.Center)

@Composable
private fun CodeText(code: String) = Text(code, style = SukifyType.Title.copy(fontSize = 28.sp, letterSpacing = 4.sp), color = SukifyColors.Text)

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .bounceClick(enabled = enabled, pressed = 0.96f, onClick = onClick)
            .background(if (enabled) SukifyColors.Accent else SukifyColors.Chip, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SukifyType.BodyBold, color = if (enabled) Color.Black else SukifyColors.TextMuted)
    }
}
