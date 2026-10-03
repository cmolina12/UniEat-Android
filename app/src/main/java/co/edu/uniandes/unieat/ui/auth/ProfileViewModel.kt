package co.edu.uniandes.unieat.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.uniandes.unieat.core.model.MeResponse
import co.edu.uniandes.unieat.data.auth.SessionManager
import co.edu.uniandes.unieat.data.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ProfileState {
    data object Loading : ProfileState
    data class Content(val me: MeResponse) : ProfileState
    data class Error(val message: String) : ProfileState
}

class ProfileViewModel(
    private val repository: ProfileRepository,
    private val sessions: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow<ProfileState>(ProfileState.Loading)
    val state: StateFlow<ProfileState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = ProfileState.Loading
            try {
                _state.value = ProfileState.Content(repository.me())
            } catch (e: Exception) {
                _state.value = ProfileState.Error(e.message ?: "No se pudo cargar el perfil.")
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
