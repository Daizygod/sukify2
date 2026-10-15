package ru.nepalimsya.sukify.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.nepalimsya.sukify.core.datastore.SessionState
import ru.nepalimsya.sukify.core.datastore.SessionStore
import ru.nepalimsya.sukify.data.model.UserDto

sealed interface AppState {
    data object Loading : AppState

    data class LoggedOut(val server: String) : AppState

    data class LoggedIn(val user: UserDto) : AppState
}

@HiltViewModel
class AppViewModel @Inject constructor(sessions: SessionStore) : ViewModel() {

    val state: StateFlow<AppState> = sessions.state
        .map {
            when (it) {
                SessionState.Loading -> AppState.Loading
                is SessionState.LoggedOut -> AppState.LoggedOut(it.server)
                is SessionState.Active -> AppState.LoggedIn(it.session.user)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppState.Loading)
}
