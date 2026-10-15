package ru.nepalimsya.sukify.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.nepalimsya.sukify.connect.ConnectManager
import ru.nepalimsya.sukify.core.datastore.SessionStore
import ru.nepalimsya.sukify.data.auth.AuthRepository
import ru.nepalimsya.sukify.data.catalog.CatalogRepository
import ru.nepalimsya.sukify.data.library.LibraryState
import ru.nepalimsya.sukify.data.model.AppReleaseDto
import ru.nepalimsya.sukify.data.model.UserDto
import ru.nepalimsya.sukify.data.offline.OfflineCoordinator
import ru.nepalimsya.sukify.data.prefs.UiPrefs
import ru.nepalimsya.sukify.jam.JamManager
import ru.nepalimsya.sukify.playback.PlayContexts
import ru.nepalimsya.sukify.playback.PlayLogger
import ru.nepalimsya.sukify.playback.PlaybackConnection
import ru.nepalimsya.sukify.playback.PlaybackEngine
import ru.nepalimsya.sukify.playback.PlaybackSettings
import ru.nepalimsya.sukify.update.AppUpdater
import ru.nepalimsya.sukify.update.UpdateState
import javax.inject.Inject

/**
 * Что поднимается при входе в приложение (bootSession в вебе): медиатека,
 * настройки воспроизведения, прошлая сессия плеера и неотправленные
 * прослушивания. Плюс подключение к сервису воспроизведения.
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val library: LibraryState,
    settings: PlaybackSettings,
    engine: PlaybackEngine,
    logger: PlayLogger,
    catalog: CatalogRepository,
    prefs: UiPrefs,
    sessions: SessionStore,
    private val auth: AuthRepository,
    private val connection: PlaybackConnection,
    offline: OfflineCoordinator,
    connect: ConnectManager,
    jam: JamManager,
    private val updater: AppUpdater,
    val contexts: PlayContexts,
) : ViewModel() {

    val user: Flow<UserDto?> = sessions.user

    /** Есть релизы новее последнего просмотра «Что нового» — точка на аватаре. */
    val hasNews: StateFlow<Boolean> = combine(catalog.notifications(), prefs.bellSeen) { state, seen ->
        val latest = state.dataOrNull?.firstOrNull()?.releaseDate.orEmpty()
        latest.isNotEmpty() && latest > seen
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Автообновление: шит «Доступна версия …» поверх приложения. */
    val updateState: StateFlow<UpdateState> = updater.state
    val updatePrompt: StateFlow<Boolean> = updater.prompt
    val currentVersion: String get() = updater.currentVersion

    init {
        connection.connect()
        // Загрузки: сверка с сервером и события медиатеки.
        offline.start()
        // Sukify Connect: телефон в списке устройств, пульт для других.
        connect.start()
        prefs.trackContexts(engine)
        viewModelScope.launch { settings.refresh() }
        viewModelScope.launch {
            library.restoreCached()
            library.refresh()
        }
        viewModelScope.launch { engine.restoreSession() }
        // Джем переживает перезапуск приложения.
        viewModelScope.launch { jam.restore() }
        viewModelScope.launch { logger.flush() }
        viewModelScope.launch { auth.refreshUser() }
        // Свежая сборка на сервере — не чаще раза в 6 часов и не мешая старту.
        viewModelScope.launch {
            delay(3_000)
            updater.check(manual = false)
        }
    }

    fun startUpdate(release: AppReleaseDto) {
        viewModelScope.launch { updater.downloadAndInstall(release) }
    }

    fun skipUpdate(release: AppReleaseDto) = updater.skip(release)

    fun dismissUpdate() = updater.dismissPrompt()

    fun refreshLibrary() {
        viewModelScope.launch { library.refresh() }
    }

    fun logout() {
        viewModelScope.launch { auth.logout() }
    }

    override fun onCleared() {
        connection.disconnect()
    }
}
