package co.edu.uniandes.unieat.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.uniandes.unieat.core.model.MeResponse
import co.edu.uniandes.unieat.data.auth.AuthState
import co.edu.uniandes.unieat.data.auth.SessionManager
import co.edu.uniandes.unieat.data.local.ProfileCache
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

sealed interface AppProfileState {
    data object Loading : AppProfileState
    /** [saved]: the last profile stored on this device, shown because GET /me could not reach the server. */
    data class Content(val profile: MeResponse, val saved: Boolean = false) : AppProfileState
    data class Error(val message: String) : AppProfileState
    data object Unavailable : AppProfileState
}

/** Owns app-level session/profile state so navigation does not perform repository work in composition. */
class AppShellViewModel(
    private val sessions: SessionManager,
    private val profiles: ProfileRepository,
    private val demo: Boolean,
    /** Validated-connection changes; going back online retries a failed GET /me. */
    online: Flow<Boolean> = emptyFlow(),
    /** Last profile of each sign-in, so the role survives opening the app offline. null in demo. */
    private val cache: ProfileCache? = null,
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
        viewModelScope.launch {
            // Also refresh a saved profile: the role may have changed on the server meanwhile.
            online.collect { if (it && needsRefresh()) loadProfile() }
        }
    }

    fun retryProfile() {
        viewModelScope.launch { loadProfile() }
    }

    private fun needsRefresh(): Boolean = when (val state = _profileState.value) {
        is AppProfileState.Error -> true
        is AppProfileState.Content -> state.saved
        else -> false
    }

    private suspend fun loadProfile() {
        val userId = (sessions.state.value as? AuthState.SignedIn)?.session?.userId
        // Keep the saved profile on screen while refreshing it, so the tabs do not disappear and come back.
        if (_profileState.value !is AppProfileState.Content) _profileState.value = AppProfileState.Loading
        _profileState.value = try {
            val profile = profiles.me()
            if (userId != null) cache?.save(userId, profile)
            AppProfileState.Content(profile)
        } catch (e: Exception) {
            // Only "no internet" uses the saved copy: an expired session or a server error must stay visible.
            val saved = if (e is ApiException && e.code == ApiException.OFFLINE && userId != null) cache?.load(userId) else null
            if (saved != null) AppProfileState.Content(saved, saved = true)
            else AppProfileState.Error(e.message ?: "No se pudo cargar el perfil.")
        }
    }
}
