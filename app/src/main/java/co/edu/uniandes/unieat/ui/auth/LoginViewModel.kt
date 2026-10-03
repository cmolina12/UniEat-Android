package co.edu.uniandes.unieat.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.uniandes.unieat.data.auth.AuthState
import co.edu.uniandes.unieat.data.auth.SessionManager
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LoginState {
    data object Idle : LoginState
    data object Loading : LoginState
    data object Success : LoginState
    data class Error(val message: String) : LoginState
}

class LoginViewModel(private val sessions: SessionManager) : ViewModel() {
    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessions.state.collect {
                if (it is AuthState.SignedIn) _state.value = LoginState.Success
            }
        }
    }

    fun signIn(email: String, password: String) {
        if (_state.value is LoginState.Loading) return
        if (!EMAIL_REGEX.matches(email.trim())) {
            _state.value = LoginState.Error("Escribe un correo válido.")
            return
        }
        if (password.isBlank()) {
            _state.value = LoginState.Error("Escribe tu contraseña.")
            return
        }

        viewModelScope.launch {
            _state.value = LoginState.Loading
            try {
                sessions.signIn(email, password)
                _state.value = LoginState.Success
            } catch (e: ApiException) {
                _state.value = LoginState.Error(e.message ?: "No fue posible iniciar sesión.")
            } catch (_: Exception) {
                _state.value = LoginState.Error("Ocurrió un error inesperado.")
            }
        }
    }

    companion object {
        internal val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}
