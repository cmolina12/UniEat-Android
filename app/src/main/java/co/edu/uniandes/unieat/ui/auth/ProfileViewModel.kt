package co.edu.uniandes.unieat.ui.auth

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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

sealed interface ProfileState {
    data object Loading : ProfileState
    /** saved: profile from this device, shown because GET /me couldn't reach the server. */
    data class Content(val me: MeResponse, val saved: Boolean = false) : ProfileState
    data class Error(val message: String) : ProfileState
}

class ProfileViewModel(
    private val repository: ProfileRepository,
    private val sessions: SessionManager,
    /** Last GET /me saved by the app shell (DataStore), so Perfil opens offline. */
    private val cache: ProfileCache? = null,
    /** Validated-connection changes: a saved or failed profile reloads when the signal returns. */
    online: Flow<Boolean> = emptyFlow(),
) : ViewModel() {
    private val _state = MutableStateFlow<ProfileState>(ProfileState.Loading)
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            online.collect { isOnline ->
                val current = _state.value
                val needsReload = current is ProfileState.Error || (current is ProfileState.Content && current.saved)
                if (isOnline && needsReload) refresh()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = ProfileState.Loading
            _state.value = try {
                ProfileState.Content(repository.me())
            } catch (e: Exception) {
                savedProfile(e) ?: ProfileState.Error(e.message ?: "No se pudo cargar el perfil.")
            }
        }
    }

    /** Only "no internet" uses the saved profile, and only the signed-in user's. */
    private suspend fun savedProfile(e: Exception): ProfileState? {
        if (e !is ApiException || e.code != ApiException.OFFLINE) return null
        val userId = (sessions.state.value as? AuthState.SignedIn)?.session?.userId ?: return null
        return cache?.load(userId)?.let { ProfileState.Content(it, saved = true) }
    }

    /** Reloads without Loading so the screen doesn't blink (after "Actualizar estado"). */
    fun refresh() {
        viewModelScope.launch {
            try {
                _state.value = ProfileState.Content(repository.me())
            } catch (_: Exception) {
                // keep the current profile, the establishments section shows its own message
            }
        }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            sessions.signOut()
            onDone()
        }
    }
}
