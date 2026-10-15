package ru.nepalimsya.sukify.core.datastore

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.nepalimsya.sukify.BuildConfig
import ru.nepalimsya.sukify.core.network.ApiHost
import ru.nepalimsya.sukify.data.model.UserDto
import ru.nepalimsya.sukify.di.ApplicationScope
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")

/** Вошедший пользователь: сервер, токен Sanctum и запись устройства на сервере. */
@Serializable
data class Session(
    val server: String,
    val token: String,
    val deviceId: Long? = null,
    val user: UserDto,
)

sealed interface SessionState {
    /** Ещё читаем с диска — показываем сплэш. */
    data object Loading : SessionState

    data class LoggedOut(val server: String) : SessionState

    data class Active(val session: Session) : SessionState
}

/**
 * Сессия входа. Токен шифруется ключом из Android Keystore (SecretBox);
 * в памяти держим расшифрованную копию — её читает сетевой слой на каждом
 * запросе без похода на диск.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secretBox: SecretBox,
    private val json: Json,
    private val apiHost: ApiHost,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Вошедший пользователь (имя, аватар) — для шапок экранов. */
    val user: Flow<UserDto?> = _state.map { (it as? SessionState.Active)?.session?.user }.distinctUntilChanged()

    /** Текущая сессия или null — для интерцепторов и сервисов. */
    val current: Session? get() = (_state.value as? SessionState.Active)?.session

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        val prefs = context.sessionDataStore.data.first()
        val server = prefs[SERVER] ?: BuildConfig.DEFAULT_SERVER
        val session = prefs[BLOB]?.let { decode(it) }

        apiHost.update(session?.server ?: server)
        _state.value = session?.let { SessionState.Active(it) } ?: SessionState.LoggedOut(server)
    }

    suspend fun save(session: Session) {
        val blob = withContext(Dispatchers.IO) {
            Base64.encodeToString(
                secretBox.seal(json.encodeToString(Session.serializer(), session).toByteArray()),
                Base64.NO_WRAP,
            )
        }
        context.sessionDataStore.edit {
            it[BLOB] = blob
            it[SERVER] = session.server
        }
        apiHost.update(session.server)
        _state.value = SessionState.Active(session)
    }

    /** Обновить пользователя (имя, аватар) без перевыпуска токена. */
    suspend fun updateUser(user: UserDto) {
        current?.let { save(it.copy(user = user)) }
    }

    suspend fun clear() {
        val server = current?.server ?: apiHost.base
        context.sessionDataStore.edit { it.remove(BLOB) }
        _state.value = SessionState.LoggedOut(server)
    }

    /** Сервер пометил токен недействительным (отвязали в вебе, забанили). */
    fun onUnauthorized() {
        if (current != null) scope.launch { clear() }
    }

    /**
     * Постоянный id устройства для Sukify Connect: переживает выход и вход,
     * чтобы телефон не плодил «новые устройства» в списках пультов.
     */
    suspend fun connectDeviceId(): String {
        context.sessionDataStore.data.first()[DEVICE]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.sessionDataStore.edit { it[DEVICE] = id }
        return id
    }

    private fun decode(blob: String): Session? = runCatching {
        val plain = secretBox.open(Base64.decode(blob, Base64.NO_WRAP))
        json.decodeFromString(Session.serializer(), String(plain))
    }.getOrNull()

    private companion object {
        val BLOB = stringPreferencesKey("blob")
        val SERVER = stringPreferencesKey("server")
        val DEVICE = stringPreferencesKey("connect_device")
    }
}
