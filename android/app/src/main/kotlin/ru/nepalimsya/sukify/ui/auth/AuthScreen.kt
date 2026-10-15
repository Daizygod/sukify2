package ru.nepalimsya.sukify.ui.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import ru.nepalimsya.sukify.R
import ru.nepalimsya.sukify.ui.components.PrimaryButton
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType

/** Вход и регистрация — как LoginPage.vue веба, в мобильной раскладке. */
@Composable
fun AuthScreen(defaultServer: String, vm: AuthViewModel = hiltViewModel()) {
    LaunchedEffect(defaultServer) {
        if (vm.server.isBlank()) vm.server = defaultServer
    }
    AuthContent(vm)
}

@Composable
fun AuthContent(vm: AuthForm) {
    val focus = LocalFocusManager.current
    val register = vm.mode == AuthMode.Register
    var editServer by rememberSaveable { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1F1F1F), SukifyColors.Elevated))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            Image(painterResource(R.drawable.ic_splash), contentDescription = null, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(12.dp))
            Text("Sukify", style = SukifyType.PageTitle, color = SukifyColors.Text)
            Spacer(Modifier.height(32.dp))
            // Текстовое начертание, а не заголовочное: у того латиница весом 900,
            // а кириллица только 700 — слова в одной строке выходили разной жирности.
            Text(
                if (register) "Регистрация в Sukify" else "Войти в Sukify",
                style = SukifyType.PageTitle.copy(fontSize = SukifyType.PageTitle.fontSize * 1.2f),
                color = SukifyColors.Text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            Column(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AnimatedVisibility(register, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    AuthField(
                        label = "Имя",
                        value = vm.name,
                        onChange = { vm.name = it },
                        error = vm.fieldErrors["name"],
                        onNext = { focus.moveFocus(FocusDirection.Down) },
                        contentType = ContentType.PersonFullName,
                    )
                }
                AuthField(
                    label = "Электронная почта",
                    value = vm.email,
                    onChange = { vm.email = it },
                    keyboard = KeyboardType.Email,
                    error = vm.fieldErrors["email"],
                    onNext = { focus.moveFocus(FocusDirection.Down) },
                    contentType = ContentType.EmailAddress + ContentType.Username,
                )
                AuthField(
                    label = "Пароль",
                    value = vm.password,
                    onChange = { vm.password = it },
                    keyboard = KeyboardType.Password,
                    secret = true,
                    error = vm.fieldErrors["password"],
                    last = !register,
                    onNext = { if (register) focus.moveFocus(FocusDirection.Down) else vm.submit() },
                    contentType = if (register) ContentType.NewPassword else ContentType.Password,
                )
                AnimatedVisibility(register, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    AuthField(
                        label = "Подтверждение пароля",
                        value = vm.confirmation,
                        onChange = { vm.confirmation = it },
                        keyboard = KeyboardType.Password,
                        secret = true,
                        last = true,
                        onNext = { vm.submit() },
                        contentType = ContentType.NewPassword,
                    )
                }

                // Ошибка, уже показанная под своим полем, второй раз не нужна.
                vm.error?.takeIf { it !in vm.fieldErrors.values }?.let {
                    Text(it, style = SukifyType.BodySmall, color = SukifyColors.Error)
                }

                PrimaryButton(
                    text = if (register) "Зарегистрироваться" else "Войти",
                    onClick = {
                        focus.clearFocus()
                        vm.submit()
                    },
                    loading = vm.busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider(Modifier.widthIn(max = 420.dp), color = SukifyColors.Divider)
            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (register) "Уже есть аккаунт? " else "Нет аккаунта? ",
                    style = SukifyType.BodySmall,
                    color = SukifyColors.TextSubdued,
                )
                Text(
                    if (register) "Войти" else "Зарегистрироваться",
                    style = SukifyType.BodySmallBold,
                    color = SukifyColors.Text,
                    modifier = Modifier.clickable { vm.toggleMode() }.padding(vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            // Свой сервер: для локального стенда или другого домена Sukify.
            if (editServer) {
                AuthField(
                    label = "Адрес сервера",
                    value = vm.server,
                    onChange = { vm.server = it },
                    keyboard = KeyboardType.Uri,
                    last = true,
                    onNext = { editServer = false },
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            } else {
                Text(
                    "Сервер: ${vm.server.removePrefix("https://")} · изменить",
                    style = SukifyType.Caption,
                    color = SukifyColors.TextMuted,
                    modifier = Modifier.clickable { editServer = true }.padding(8.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun AuthField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    error: String? = null,
    last: Boolean = false,
    onNext: () -> Unit = {},
    contentType: ContentType? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            imeAction = if (last) ImeAction.Done else ImeAction.Next,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onNext = { onNext() }, onDone = { onNext() }),
        shape = RoundedCornerShape(6.dp),
        textStyle = SukifyType.Body,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SukifyColors.Text,
            unfocusedBorderColor = Color(0xFF727272),
            focusedLabelColor = SukifyColors.Text,
            unfocusedLabelColor = SukifyColors.TextSubdued,
            cursorColor = SukifyColors.Accent,
            focusedContainerColor = SukifyColors.Elevated,
            unfocusedContainerColor = SukifyColors.Elevated,
        ),
        modifier = modifier
            .fillMaxWidth()
            .then(if (contentType != null) Modifier.semantics { this.contentType = contentType } else Modifier),
    )
}
