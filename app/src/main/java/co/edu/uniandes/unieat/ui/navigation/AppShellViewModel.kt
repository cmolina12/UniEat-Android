package co.edu.uniandes.unieat.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.uniandes.unieat.core.model.MeResponse
import co.edu.uniandes.unieat.data.auth.AuthState
import co.edu.uniandes.unieat.data.auth.SessionManager
import co.edu.uniandes.unieat.data.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface AppProfileState {
    data object Loading : AppProfileState
    data class Content(val profile: MeResponse) : AppProfileState
    data class Error(val message: String) : AppProfileState
    data object Unavailable : AppProfileState
}

/** Owns app-level session/profile state so navigation does not perform repository work in composition. */
class AppShellViewModel(
    private val sessions: SessionManager,
    private val profiles: ProfileRepository,
    private val demo: Boolean,
) : ViewModel() {
    private val _profileState = MutableStateFlow<AppProfileState>(AppProfileState.Loading)
    val profileState: StateFlow<AppProfileState> = _profileState.asStateFlow()

    init {
        viewModelScope.launch {
            // A token refresh emits a new SignedIn with the same user. Only a different user
            // (or signing out) needs a new GET /me, so the tabs do not flicker every hour.
            sessions.state
                .map { auth -> if (auth is AuthState.SignedIn) auth.session.userId else auth }
                .distinctUntilChanged()
                .collect { key ->
                    when (key) {
                        AuthState.Loading -> Unit
                        AuthState.SignedOut -> {
                            if (demo) loadProfile() else _profileState.value = AppProfileState.Unavailable
                        }
                        else -> loadProfile()
                    }
                }
        }
    }

    fun retryProfile() {
        viewModelScope.launch { loadProfile() }
    }

    private suspend fun loadProfile() {
        _profileState.value = AppProfileState.Loading
        _profileState.value = try {
            AppProfileState.Content(profiles.me())
        } catch (e: Exception) {
            AppProfileState.Error(e.message ?: "No se pudo cargar el perfil.")
        }
    }
}
