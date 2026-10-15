package ru.nepalimsya.sukify.data.auth

import android.os.Build
import ru.nepalimsya.sukify.connect.ConnectManager
import ru.nepalimsya.sukify.core.datastore.Session
import ru.nepalimsya.sukify.core.datastore.SessionStore
import ru.nepalimsya.sukify.core.network.ApiHost
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.data.cache.ApiCache
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.model.LoginRequest
import ru.nepalimsya.sukify.data.model.RegisterRequest
import ru.nepalimsya.sukify.data.model.TokenResponse
import ru.nepalimsya.sukify.data.offline.OfflineCoordinator
import ru.nepalimsya.sukify.data.prefs.UiPrefs
import ru.nepalimsya.sukify.jam.JamManager
import ru.nepalimsya.sukify.playback.PlaybackEngine
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: SukifyApi,
    private val sessions: SessionStore,
    private val apiHost: ApiHost,
    private val library: LibraryState,
    private val cache: ApiCache,
    private val prefs: UiPrefs,
    // Загрузки стираются при выходе — лениво, как и плеер.
    private val offline: Provider<OfflineCoordinator>,
    // Плеер берём лениво: он тяжёлый, а входу он не нужен.
    private val engine: Provider<PlaybackEngine>,
    private val connect: Provider<ConnectManager>,
    private val jam: Provider<JamManager>,
) {
    suspend fun login(server: String, email: String, password: String): Result<Unit> {
        apiHost.update(server)
        return apiCall {
            api.login(LoginRequest(email.trim(), password, deviceName()))
        }.mapCatching { store(it) }
    }

    suspend fun register(server: String, name: String, email: String, password: String, confirmation: String): Result<Unit> {
        apiHost.update(server)
        return apiCall {
            api.register(RegisterRequest(name.trim(), email.trim(), password, confirmation, deviceName()))
        }.mapCatching { store(it) }
    }

    /** Свежие имя и аватар — после правки профиля в вебе. */
    suspend fun refreshUser(): Result<Unit> =
        apiCall { api.me().data }.mapCatching { sessions.updateUser(it) }

    /**
     * Выход: сервер гасит токен, телефон забывает сессию — даже без сети.
     * Чужая очередь и лайки не должны всплыть у следующего пользователя.
     */
    suspend fun logout() {
        // Попрощаться в канале, пока он ещё открыт.
        connect.get().stop()
        jam.get().forget()
        apiCall { api.logout() }
        engine.get().reset()
        offline.get().stop()
        library.clearOutbox()
        library.clear()
        cache.clear()
        prefs.clearPersonal()
        sessions.clear()
    }

    private suspend fun store(response: TokenResponse) {
        sessions.save(
            Session(
                server = apiHost.base,
                token = response.token,
                deviceId = response.deviceId,
                user = response.user,
            ),
        )
    }

    companion object {
        /** «Google Pixel 8» — так телефон подписан в списке устройств веба. */
        fun deviceName(): String {
            val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        }
    }
}
