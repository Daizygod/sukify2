package ru.nepalimsya.sukify.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.model.AppReleaseDto
import ru.nepalimsya.sukify.ui.components.Toasts

/** Что с обновлением приложения — для шита «Доступна версия …» и настроек. */
@Immutable
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val release: AppReleaseDto) : UpdateState

    data class Downloading(val release: AppReleaseDto, val progress: Float) : UpdateState

    /** Ждём подтверждения установки от системы. */
    data class Installing(val release: AppReleaseDto) : UpdateState

    data class Failed(val release: AppReleaseDto?, val message: String) : UpdateState
}

/**
 * Автообновление (APK с сервера, не из магазина): CI кладёт сборку и
 * latest.json в S3, сервер отдаёт их на GET /app/android. Приложение само
 * проверяет при старте (не чаще раза в 6 часов) и по кнопке в настройках,
 * качает APK, сверяет sha256 и отдаёт системе на установку поверх себя.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SukifyApi,
    private val http: OkHttpClient,
) {
    private val prefs by lazy { context.getSharedPreferences("updates", Context.MODE_PRIVATE) }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Показать шит «Доступна версия» (а не только строку в настройках). */
    private val _prompt = MutableStateFlow(false)
    val prompt: StateFlow<Boolean> = _prompt.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    fun dismissPrompt() {
        _prompt.value = false
    }

    /** [manual] — по кнопке: всегда и с ответом «у тебя последняя версия». */
    suspend fun check(manual: Boolean) {
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_EVERY_MS) return
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        _state.value = UpdateState.Checking
        val release = apiCall { api.androidRelease().data }.getOrElse {
            _state.value = UpdateState.Idle
            if (manual) Toasts.show("Не удалось проверить обновления")
            return
        }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        if (release != null && release.versionCode > BuildConfig.VERSION_CODE) {
            _state.value = UpdateState.Available(release)
            // Отложенную версию сами не навязываем — только по кнопке.
            if (manual || prefs.getLong(KEY_SKIPPED, 0) < release.versionCode) _prompt.value = true
        } else {
            _state.value = UpdateState.UpToDate
            if (manual) Toasts.show("У тебя последняя версия")
        }
    }

    /** «Позже»: эту версию при старте больше не предлагаем. */
    fun skip(release: AppReleaseDto) {
        prefs.edit().putLong(KEY_SKIPPED, release.versionCode).apply()
        _prompt.value = false
    }

    /** Нужно разрешение «Установка из этого источника» — отправляем в системные настройки. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    suspend fun downloadAndInstall(release: AppReleaseDto) {
        if (!canInstall()) {
            Toasts.show("Разреши установку из Sukify и нажми «Обновить» ещё раз")
            openInstallPermission()
            return
        }
        val file = runCatching { download(release) }.getOrElse {
            Log.w(TAG, "download failed", it)
            _state.value = UpdateState.Failed(release, "Не удалось скачать обновление")
            return
        }
        _state.value = UpdateState.Installing(release)
        runCatching { install(file) }.onFailure {
            Log.w(TAG, "install failed", it)
            _state.value = UpdateState.Failed(release, "Не удалось начать установку")
        }
    }

    private suspend fun download(release: AppReleaseDto): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Прошлые недокачки и старые сборки — не копим.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "sukify-${release.versionCode}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        _state.value = UpdateState.Downloading(release, 0f)
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: release.size ?: -1
            var done = 0L
            var lastReport = 0L
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > total / 100) {
                            lastReport = done
                            _state.value = UpdateState.Downloading(release, done.toFloat() / total)
                        }
                    }
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = release.sha256?.lowercase()
        if (expected != null && expected != sha) {
            target.delete()
            error("sha256 не совпал: $sha")
        }
        target
    }

    private fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("sukify.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java).setAction(ACTION_STATUS)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    /** Ответ системы об установке. */
    internal fun onInstallStatus(status: Int, message: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.UpToDate
            // Пользователь отказался в системном окне — вернём кнопку.
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> _state.value = UpdateState.Failed(release, message?.takeIf { it.isNotBlank() } ?: "Установка не удалась")
        }
    }

    internal companion object {
        const val TAG = "SukifyUpdate"
        const val ACTION_STATUS = "ru.nepalimsya.sukify.UPDATE_STATUS"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_SKIPPED = "skipped_version"
        const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
    }
}

/** Принимает от PackageInstaller «нужно подтверждение» и итог установки. */
@AndroidEntryPoint
class InstallReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: AppUpdater

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppUpdater.ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Системное окно «Обновить приложение?» — показываем поверх.
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let { confirm ->
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
