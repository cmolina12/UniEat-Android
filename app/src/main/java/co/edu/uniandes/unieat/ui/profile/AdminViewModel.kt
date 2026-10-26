package co.edu.uniandes.unieat.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.uniandes.unieat.core.model.AdminUser
import co.edu.uniandes.unieat.core.model.PendingMembership
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.AdminRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminUiState(
    val requests: List<PendingMembership> = emptyList(),
    val users: List<AdminUser> = emptyList(),
    /** Request or user being changed; buttons stay disabled meanwhile. */
    val workingId: String? = null,
    /** User waiting for the "¿Conceder/Quitar…?" confirmation. */
    val confirming: AdminUser? = null,
    val requestsMessage: String? = null,
    val usersMessage: String? = null,
)

/** Admin side of Perfil, same as iOS AdminApprovalsView and AdminRolesView. */
class AdminViewModel(private val admin: AdminRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    init {
        // Both lists are independent: they load in parallel and one failing does not hide the other.
        viewModelScope.launch {
            val requests = async { loadRequests() }
            val users = async { loadUsers() }
            requests.await()
            users.await()
        }
    }

    fun refreshRequests() {
        viewModelScope.launch { loadRequests() }
    }

    fun refreshUsers() {
        viewModelScope.launch { loadUsers() }
    }

    fun approve(request: PendingMembership) {
        if (_state.value.workingId != null) return
        val id = request.key
        _state.update { it.copy(workingId = id) }
        viewModelScope.launch {
            try {
                admin.approve(request)
                _state.update { state -> state.copy(requests = state.requests.filterNot { it.key == id }) }
                loadRequests()
                _state.update {
                    it.copy(requestsMessage = "${request.establishmentName} aprobado. El dueño debe actualizar su estado en la app.")
                }
            } catch (e: ApiException) {
                _state.update { it.copy(requestsMessage = e.error.message) }
            } finally {
                _state.update { it.copy(workingId = null) }
            }
        }
    }

    fun askRoleChange(user: AdminUser) = _state.update { it.copy(confirming = user) }

    fun cancelRoleChange() = _state.update { it.copy(confirming = null) }

    fun confirmRoleChange() {
        val user = _state.value.confirming ?: return
        _state.update { it.copy(confirming = null, workingId = user.id) }
        viewModelScope.launch {
            try {
                admin.setAdmin(user.id, admin = user.role != "admin")
                loadUsers()
                _state.update { it.copy(usersMessage = "Rol actualizado. El cambio se aplicará al actualizar la cuenta del usuario.") }
            } catch (e: ApiException) {
                _state.update { it.copy(usersMessage = e.error.message) }
            } finally {
                _state.update { it.copy(workingId = null) }
            }
        }
    }

    private suspend fun loadRequests() {
        try {
            val requests = admin.pendingMemberships()
            _state.update { it.copy(requests = requests, requestsMessage = null) }
        } catch (e: ApiException) {
            _state.update { it.copy(requestsMessage = e.error.message) }
        }
    }

    private suspend fun loadUsers() {
        try {
            val users = admin.users()
            _state.update { it.copy(users = users, usersMessage = null) }
        } catch (e: ApiException) {
            _state.update { it.copy(usersMessage = e.error.message) }
        }
    }
}

/** A user can request several establishments, so the pair identifies the request. */
val PendingMembership.key: String get() = "$establishmentId:$userId"

fun roleName(role: String?): String = when (role) {
    "admin" -> "Administrador"
    "restaurant" -> "Restaurante"
    else -> "Estudiante"
}
