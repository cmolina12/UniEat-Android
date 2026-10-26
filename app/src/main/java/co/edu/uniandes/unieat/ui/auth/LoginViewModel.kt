package co.edu.uniandes.unieat.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.uniandes.unieat.data.auth.AuthState
import co.edu.uniandes.unieat.data.auth.SessionManager
import co.edu.uniandes.unieat.data.auth.SupabaseAuthRepository
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LoginState {
    data object Idle : LoginState
    data object Loading : LoginState
    data object Success : LoginState
    /** Account created, but Supabase requires confirming the email before the first sign-in. */
    data object ConfirmEmail : LoginState
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

        submit("No fue posible iniciar sesión.") {
            sessions.signIn(email, password)
            LoginState.Success
        }
    }

    fun signUp(displayName: String, email: String, password: String) {
        if (_state.value is LoginState.Loading) return
        val error = when {
            displayName.isBlank() -> "Escribe tu nombre."
            !EMAIL_REGEX.matches(email.trim()) -> "Escribe un correo válido."
            password.length < SupabaseAuthRepository.MIN_PASSWORD_LENGTH ->
                "La contraseña debe tener al menos ${SupabaseAuthRepository.MIN_PASSWORD_LENGTH} caracteres."
            else -> null
        }
        if (error != null) {
            _state.value = LoginState.Error(error)
            return
        }

        submit("No fue posible crear la cuenta.") {
            if (sessions.signUp(email, password, displayName)) LoginState.Success else LoginState.ConfirmEmail
        }
    }

    /** Clears an error or notice when switching between signing in and creating an account. */
    fun resetMessage() {
        if (_state.value is LoginState.Error || _state.value is LoginState.ConfirmEmail) _state.value = LoginState.Idle
    }

    private fun submit(fallbackError: String, action: suspend () -> LoginState) {
        viewModelScope.launch {
            _state.value = LoginState.Loading
            _state.value = try {
                action()
            } catch (e: ApiException) {
                LoginState.Error(e.message ?: fallbackError)
            } catch (_: Exception) {
                LoginState.Error("Ocurrió un error inesperado.")
            }
        }
    }

    companion object {
        internal val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }
}
