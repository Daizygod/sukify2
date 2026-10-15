package ru.nepalimsya.sukify.ui.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.core.datastore.SessionStore
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.core.network.apiError
import ru.nepalimsya.sukify.data.auth.AuthRepository
import ru.nepalimsya.sukify.data.model.DesktopDeviceDto
import ru.nepalimsya.sukify.data.model.DiscordDto
import ru.nepalimsya.sukify.data.model.UserDto
import ru.nepalimsya.sukify.playback.MediaSources
import ru.nepalimsya.sukify.playback.PlaybackPrefs
import ru.nepalimsya.sukify.playback.PlaybackSettings
import ru.nepalimsya.sukify.playback.audio.EqSettings
import ru.nepalimsya.sukify.playback.audio.EqualizerStore
import ru.nepalimsya.sukify.playback.audio.UserEq
import ru.nepalimsya.sukify.ui.components.AvatarImage
import ru.nepalimsya.sukify.ui.components.PageScaffold
import ru.nepalimsya.sukify.ui.components.PillButton
import ru.nepalimsya.sukify.ui.components.SettingRow
import ru.nepalimsya.sukify.ui.components.SettingSwitchRow
import ru.nepalimsya.sukify.ui.components.SettingsSection
import ru.nepalimsya.sukify.ui.components.ThinSlider
import ru.nepalimsya.sukify.ui.components.Toasts
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.shell.DeepLinks
import ru.nepalimsya.sukify.ui.shell.DownloadsRoute
import ru.nepalimsya.sukify.ui.shell.EqualizerRoute
import ru.nepalimsya.sukify.ui.shell.ImportRoute
import ru.nepalimsya.sukify.ui.shell.LinkDeviceRoute
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.StatsRoute
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.update.AppUpdater
import ru.nepalimsya.sukify.update.UpdateState
import ru.nepalimsya.sukify.widget.SukifyWidgetReceiver
import ru.nepalimsya.sukify.util.Format
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.roundToInt

data class SettingsUi(
    val user: UserDto? = null,
    val name: String = "",
    val handle: String = "",
    val savingProfile: Boolean = false,
    val discord: DiscordDto? = null,
    val discordConfigured: Boolean = true,
    val discordBusy: Boolean = false,
    val devices: List<DesktopDeviceDto> = emptyList(),
    val cacheBytes: Long? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SukifyApi,
    private val sessions: SessionStore,
    private val auth: AuthRepository,
    private val playbackSettings: PlaybackSettings,
    private val sources: MediaSources,
    val updater: AppUpdater,
    equalizerStore: EqualizerStore,
) : ViewModel() {
    val equalizer: StateFlow<EqSettings> = equalizerStore.settings

    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    val prefs: StateFlow<PlaybackPrefs> = playbackSettings.prefs
    val update: StateFlow<UpdateState> = updater.state

    /** Свой телефон в списке устройств — его не отвязывают, из него выходят. */
    val myDeviceId: Long? get() = sessions.current?.deviceId

    init {
        sessions.current?.user?.let { u -> _ui.update { it.copy(user = u, name = u.name, handle = u.username.orEmpty()) } }
        viewModelScope.launch { playbackSettings.refresh() }
        refreshCache()
    }

    /** Вернулись на экран (например, из браузера после привязки Discord) — перечитываем. */
    fun reload() {
        viewModelScope.launch {
            apiCall { api.discord() }.onSuccess { d -> _ui.update { it.copy(discord = d.data, discordConfigured = d.configured) } }
            apiCall { api.desktopDevices().data }.onSuccess { list -> _ui.update { it.copy(devices = list) } }
        }
    }

    private fun refreshCache() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { sources.cacheBytes() }.getOrNull() }
            _ui.update { it.copy(cacheBytes = bytes) }
        }
    }

    // --- Профиль ----------------------------------------------------------------------

    fun setName(v: String) = _ui.update { it.copy(name = v.take(255)) }

    fun setHandle(v: String) = _ui.update { it.copy(handle = v.filter { c -> c.isLetterOrDigit() || c == '_' || c == '.' || c == '-' }.take(30)) }

    fun saveProfile() {
        val s = _ui.value
        _ui.update { it.copy(savingProfile = true) }
        viewModelScope.launch {
            apiCall { api.saveProfile(buildJsonObject { put("name", s.name.trim()); put("username", s.handle.trim()) }).data }
                .onSuccess { u ->
                    sessions.updateUser(u)
                    _ui.update { it.copy(user = u) }
                    Toasts.show("Профиль обновлён")
                }
                .onFailure {
                    Toasts.show(if (it.apiError.code == 422) "Такой @handle уже занят или содержит недопустимые символы" else "Не удалось сохранить профиль")
                }
            _ui.update { it.copy(savingProfile = false) }
        }
    }

    fun uploadAvatar(uri: Uri) {
        viewModelScope.launch {
            val part = withContext(Dispatchers.IO) { avatarPart(uri) }
            if (part == null) {
                Toasts.show("Не подошёл файл — нужен JPEG, PNG или WebP")
                return@launch
            }
            apiCall { api.uploadAvatar(part).data }
                .onSuccess { u ->
                    sessions.updateUser(u)
                    _ui.update { it.copy(user = u) }
                    Toasts.show("Аватарка обновлена")
                }
                .onFailure { Toasts.show("Не подошёл файл — нужен JPEG, PNG или WebP до 8 МБ") }
        }
    }

    fun removeAvatar() {
        viewModelScope.launch {
            apiCall { api.deleteAvatar().data }.onSuccess { u ->
                sessions.updateUser(u)
                _ui.update { it.copy(user = u) }
                Toasts.show("Аватарка удалена")
            }
        }
    }

    /** Фото из галереи → квадратный JPEG до 1024 px. */
    private fun avatarPart(uri: Uri): MultipartBody.Part? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val side = maxOf(info.size.width, info.size.height)
            if (side > 1024) {
                val scale = 1024f / side
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
        MultipartBody.Part.createFormData("avatar", "avatar.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
    }.getOrNull()

    // --- Воспроизведение --------------------------------------------------------------

    private var saveJob: Job? = null

    /** Сохраняем с паузой: ползунок кроссфейда не должен слать запрос на каждое деление. */
    fun savePrefs(prefs: PlaybackPrefs) {
        playbackSettings.update(prefs)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            if (playbackSettings.save(prefs)) Toasts.show("Настройки сохранены")
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sources.clearCache() }
            refreshCache()
            Toasts.show("Кэш прослушанного очищен")
        }
    }

    // --- Discord ------------------------------------------------------------------------

    fun connectDiscord(open: (String) -> Unit) {
        _ui.update { it.copy(discordBusy = true) }
        viewModelScope.launch {
            apiCall { api.discordRedirect().url }
                .onSuccess(open)
                .onFailure { Toasts.show("Discord не настроен на сервере") }
            _ui.update { it.copy(discordBusy = false) }
        }
    }

    fun disconnectDiscord() {
        _ui.update { it.copy(discordBusy = true) }
        viewModelScope.launch {
            apiCall { api.unlinkDiscord() }.onSuccess {
                _ui.update { it.copy(discord = null) }
                Toasts.show("Discord отвязан")
            }
            _ui.update { it.copy(discordBusy = false) }
        }
    }

    private var discordJob: Job? = null

    fun saveDiscord(d: DiscordDto) {
        _ui.update { it.copy(discord = d) }
        discordJob?.cancel()
        discordJob = viewModelScope.launch {
            delay(400)
            val body = buildJsonObject {
                put("presence_enabled", d.presenceEnabled)
                put("show_when_paused", d.showWhenPaused)
                put("show_button", d.showButton)
                put("show_cover", d.showCover)
            }
            apiCall { api.saveDiscord(body).data }.onSuccess { saved ->
                _ui.update { it.copy(discord = saved) }
                Toasts.show("Настройки Discord сохранены")
            }
        }
    }

    fun unlinkDevice(id: Long) {
        viewModelScope.launch {
            apiCall { api.unlinkDevice(id) }.onSuccess {
                _ui.update { s -> s.copy(devices = s.devices.filterNot { it.id == id }) }
                Toasts.show("Приложение отвязано")
            }
        }
    }

    fun checkUpdates() {
        viewModelScope.launch { updater.check(manual = true) }
    }

    fun logout() {
        viewModelScope.launch { auth.logout() }
    }
}

/** Настройки (SettingsPage.vue) под телефон. */
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::uploadAvatar) }
    LifecycleResumeEffect(Unit) {
        vm.reload()
        onPauseOrDispose { }
    }

    PageScaffold("Настройки") {
        // --- Профиль ---
        item { SettingsSection("Профиль") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val user = ui.user
                if (user?.avatarUrl != null) {
                    AvatarImage(user.avatarUrl, 64.dp)
                } else {
                    Box(Modifier.size(64.dp).background(Color(0xFF509BF5), CircleShape), contentAlignment = Alignment.Center) {
                        Text((user?.name?.firstOrNull() ?: '?').uppercase(), style = SukifyType.Title.copy(fontSize = 26.sp), color = Color.Black)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Загрузить") { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    if (user?.avatarUrl != null) PillButton("Убрать", onClick = vm::removeAvatar)
                }
            }
        }
        item { Field("Отображаемое имя", "Как тебя видят другие", ui.name, vm::setName) }
        item { Field("Ссылка на профиль", "${DeepLinks.HOST}/user/${ui.handle.ifBlank { "…" }}", ui.handle, vm::setHandle, prefix = "@") }
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                PillButton(if (ui.savingProfile) "Сохраняем…" else "Сохранить профиль", primary = true, enabled = !ui.savingProfile, onClick = vm::saveProfile)
            }
        }

        // --- Воспроизведение ---
        item { SettingsSection("Воспроизведение") }
        item { CrossfadeRow(prefs.crossfadeSeconds) { vm.savePrefs(prefs.copy(crossfadeSeconds = it)) } }
        item {
            SettingSwitchRow("Нормализация громкости", "Выравнивает громкость треков, чтобы не дёргать ручку", prefs.normalizationEnabled) { on ->
                // «Выключенная» нормализация — очень тихая цель, как в вебе.
                vm.savePrefs(prefs.copy(targetLufs = if (on) -14.0 else -29.9))
            }
        }
        item {
            val eq by vm.equalizer.collectAsStateWithLifecycle()
            SettingRow(
                "Эквалайзер",
                if (!eq.enabled) "Выключен" else UserEq.PRESETS[eq.preset]?.first ?: "Свой",
                onClick = { navigator.open(EqualizerRoute) },
            )
        }
        item {
            SettingSwitchRow("Умное перемешивание", "Шафл учитывает похожесть треков, а не просто случайный порядок", prefs.smartShuffle) { on ->
                vm.savePrefs(prefs.copy(smartShuffle = on))
            }
        }

        item {
            SettingRow("Виджет на рабочий стол", "Обложка, трек и кнопки — без открытия приложения") {
                PillButton("Добавить") { pinWidget(context) }
            }
        }

        // --- Загрузки и память ---
        item { SettingsSection("Загрузки и память") }
        item { SettingRow("Загрузки", "Что скачивать, место, синхронизация", onClick = { navigator.open(DownloadsRoute) }) }
        item {
            SettingRow("Кэш прослушанного", ui.cacheBytes?.let { "Занимает ${Format.bytes(it)} — повторное прослушивание без сети" } ?: "Считаем…") {
                PillButton("Очистить", enabled = (ui.cacheBytes ?: 0) > 0, onClick = vm::clearCache)
            }
        }

        // --- Статистика и импорт ---
        item { SettingsSection("Статистика") }
        item { SettingRow("Твоя статистика", "Сколько и что слушаешь", onClick = { navigator.open(StatsRoute()) }) }
        item { SettingRow("Импорт из Spotify", "Перенести лайки, плейлисты и историю", onClick = { navigator.open(ImportRoute) }) }

        // --- Discord ---
        item { SettingsSection("Discord") }
        item {
            val discord = ui.discord
            SettingRow(
                "Аккаунт",
                when {
                    discord != null -> "Подключён как ${discord.displayName ?: discord.username} (@${discord.username})"
                    !ui.discordConfigured -> "На сервере не заданы ключи приложения Discord"
                    else -> "Нужен, чтобы видеть, к какому аккаунту привязана трансляция"
                },
            ) {
                if (discord != null) {
                    PillButton("Отвязать", enabled = !ui.discordBusy, onClick = vm::disconnectDiscord)
                } else {
                    PillButton("Привязать", primary = true, enabled = !ui.discordBusy && ui.discordConfigured) {
                        vm.connectDiscord { url -> openBrowser(context, url) }
                    }
                }
            }
        }
        ui.discord?.let { d ->
            item { SettingSwitchRow("Показывать, что я слушаю", "«Слушает Sukify» с треком, обложкой и прогрессом", d.presenceEnabled) { vm.saveDiscord(d.copy(presenceEnabled = it)) } }
            item { SettingSwitchRow("Не скрывать на паузе", "Статус остаётся, пока трек на паузе", d.showWhenPaused, enabled = d.presenceEnabled) { vm.saveDiscord(d.copy(showWhenPaused = it)) } }
            item { SettingSwitchRow("Обложка трека", "Discord скачивает картинку сам — нужен публичный адрес обложек", d.showCover, enabled = d.presenceEnabled) { vm.saveDiscord(d.copy(showCover = it)) } }
            item { SettingSwitchRow("Кнопка «Слушать в Sukify»", "Ссылка на трек под статусом", d.showButton, enabled = d.presenceEnabled) { vm.saveDiscord(d.copy(showButton = it)) } }
        }
        item {
            SettingRow("Приложение для компьютера", "Статус в Discord ставит программа на компьютере — подключи её по коду") {
                PillButton("Подключить") { navigator.open(LinkDeviceRoute()) }
            }
        }

        // --- Устройства ---
        if (ui.devices.isNotEmpty()) {
            item { SettingsSection("Приложения и устройства") }
            ui.devices.forEach { device ->
                item(key = "dev${device.id}") {
                    val mine = device.id == vm.myDeviceId
                    SettingRow(device.name, "${device.platform ?: "компьютер"} · ${if (mine) "это устройство" else seen(device.lastSeenAt)}") {
                        if (!mine) PillButton("Отвязать") { vm.unlinkDevice(device.id) }
                    }
                }
            }
        }

        // --- О приложении ---
        item { SettingsSection("О приложении") }
        item {
            SettingRow(
                "Sukify для Android",
                buildString {
                    append("Версия ${vm.updater.currentVersion}")
                    when (val u = update) {
                        UpdateState.Checking -> append(" · проверяем…")
                        UpdateState.UpToDate -> append(" · последняя")
                        is UpdateState.Available -> append(" · доступна ${u.release.versionName}")
                        is UpdateState.Downloading -> append(" · скачиваем ${(u.progress * 100).toInt()}%")
                        else -> {}
                    }
                },
            ) {
                PillButton("Проверить", enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading, onClick = vm::checkUpdates)
            }
        }
        if (BuildConfig.DEBUG) {
            item { SettingRow("Сервер", BuildConfig.DEFAULT_SERVER) }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Выйти",
                    style = SukifyType.BodyBold,
                    color = SukifyColors.Text,
                    modifier = Modifier
                        .bounceClick(pressed = 0.95f, onClick = vm::logout)
                        .background(SukifyColors.Chip, RoundedCornerShape(24.dp))
                        .padding(horizontal = 32.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Field(title: String, hint: String, value: String, onChange: (String) -> Unit, prefix: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = SukifyType.Body, color = SukifyColors.Text)
        Text(hint, style = SukifyType.Caption, color = SukifyColors.TextSubdued, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().background(SukifyColors.Chip, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            prefix?.let { Text(it, style = SukifyType.Body, color = SukifyColors.TextSubdued) }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = SukifyType.Body.copy(color = SukifyColors.Text),
                cursorBrush = SolidColor(SukifyColors.Accent),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Кроссфейд 0–12 с: ползунок двигается сразу, сохраняется с паузой. */
@Composable
private fun CrossfadeRow(seconds: Int, onChange: (Int) -> Unit) {
    var local by remember(seconds) { mutableFloatStateOf(seconds.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Кроссфейд", style = SukifyType.Body, color = SukifyColors.Text)
                Text("Плавный переход между треками, если у пары нет своего перехода", style = SukifyType.Caption, color = SukifyColors.TextSubdued)
            }
            Text("${local.toInt()} с", style = SukifyType.BodyBold, color = SukifyColors.Text)
        }
        ThinSlider(
            value = local,
            onChange = { local = it },
            range = 0f..12f,
            steps = 11,
            onFinished = { onChange(local.roundToInt()) },
        )
    }
}

/** «на связи», «5 мин назад» — как deviceSeen() в вебе. */
private fun seen(iso: String?): String {
    val at = Format.epochSeconds(iso) ?: return "ни разу не выходило на связь"
    val mins = ((System.currentTimeMillis() / 1000 - at) / 60).coerceAtLeast(0)
    return when {
        mins < 2 -> "на связи"
        mins < 60 -> "$mins мин назад"
        mins < 60 * 24 -> "${mins / 60} ч назад"
        else -> "${mins / 60 / 24} дн назад"
    }
}

/** Попросить лаунчер закрепить виджет плеера (Android 8+; лаунчер может не уметь). */
private fun pinWidget(context: Context) {
    val manager = context.getSystemService(AppWidgetManager::class.java)
    if (manager?.isRequestPinAppWidgetSupported != true) {
        Toasts.show("Лаунчер не умеет добавлять виджеты из приложения — добавь «Sukify» из списка виджетов")
        return
    }
    manager.requestPinAppWidget(ComponentName(context, SukifyWidgetReceiver::class.java), null, null)
}

private fun openBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toasts.show("Не нашлось браузера, чтобы открыть Discord") }
}
