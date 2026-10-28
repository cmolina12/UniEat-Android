package co.edu.uniandes.unieat.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.uniandes.unieat.core.model.AdminUser
import co.edu.uniandes.unieat.core.model.PendingMembership
import co.edu.uniandes.unieat.data.local.OfflineStore
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.AdminRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant

data class AdminUiState(
    val requests: List<PendingMembership> = emptyList(),
    val users: List<AdminUser> = emptyList(),
    /** Request or user being changed; buttons stay disabled meanwhile. */
    val workingId: String? = null,
    /** User waiting for the "¿Conceder/Quitar…?" confirmation. */
    val confirming: AdminUser? = null,
    val requestsMessage: String? = null,
    val usersMessage: String? = null,
    /** No validated connection: approving and changing roles wait for the signal (network only). */
    val offline: Boolean = false,
    /** Set when each list comes from the copy saved at that time instead of the server. */
    val requestsSavedAt: Instant? = null,
    val usersSavedAt: Instant? = null,
)

/** Admin side of Perfil, same as iOS AdminApprovalsView and AdminRolesView. */
class AdminViewModel(
    private val admin: AdminRepository,
    /** Last lists that loaded, shown offline. null in demo mode. */
    private val store: OfflineStore? = null,
    /** Validated-connection changes: buttons turn off without signal and both lists reload when it returns. */
    online: Flow<Boolean> = emptyFlow(),
) : ViewModel() {
    private val _state = MutableStateFlow(AdminUiState())
    val state: StateFlow<AdminUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { loadBoth() }
        viewModelScope.launch {
            online.collect { isOnline ->
                val current = _state.value
                _state.update { it.copy(offline = !isOnline) }
                val showsSavedCopy = current.requestsSavedAt != null || current.usersSavedAt != null
                if (isOnline && (current.offline || showsSavedCopy)) loadBoth()
            }
        }
    }

    /** Both lists are independent: they load in parallel and one failing does not hide the other. */
    private suspend fun loadBoth() = coroutineScope {
        val requests = async { loadRequests() }
        val users = async { loadUsers() }
        requests.await()
        users.await()
    }

    fun refreshRequests() {
        viewModelScope.launch { loadRequests() }
    }

    fun refreshUsers() {
        viewModelScope.launch { loadUsers() }
    }

    fun approve(request: PendingMembership) {
        if (_state.value.offline) return _state.update { it.copy(requestsMessage = APPROVE_OFFLINE_MESSAGE) }
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
                // Network only: the server must confirm the request is still pending, so approvals are never queued.
                _state.update { it.copy(requestsMessage = if (e.code == ApiException.OFFLINE) APPROVE_OFFLINE_MESSAGE else e.error.message) }
            } finally {
                _state.update { it.copy(workingId = null) }
            }
        }
    }

    fun askRoleChange(user: AdminUser) = _state.update {
        if (it.offline) it.copy(usersMessage = ROLE_OFFLINE_MESSAGE) else it.copy(confirming = user)
    }

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
                _state.update { it.copy(usersMessage = if (e.code == ApiException.OFFLINE) ROLE_OFFLINE_MESSAGE else e.error.message) }
            } finally {
                _state.update { it.copy(workingId = null) }
            }
        }
    }

    /** Network falling back to cache, same as the two lists below. */
    private suspend fun loadRequests() {
        try {
            val requests = admin.pendingMemberships()
            store?.save(REQUESTS_KEY, REQUESTS, requests)
            _state.update { it.copy(requests = requests, requestsMessage = null, requestsSavedAt = null) }
        } catch (e: ApiException) {
            if (e.code != ApiException.OFFLINE) return _state.update { it.copy(requestsMessage = e.error.message) }
            val saved = store?.load(REQUESTS_KEY, REQUESTS)
            _state.update {
                if (saved != null) it.copy(requests = saved.value, requestsSavedAt = saved.savedAt, requestsMessage = null, offline = true)
                else it.copy(offline = true, requestsMessage = "Sin conexión: las solicitudes se cargarán solas al volver la señal.")
            }
        }
    }

    private suspend fun loadUsers() {
        try {
            val users = admin.users()
            store?.save(USERS_KEY, USERS, users)
            _state.update { it.copy(users = users, usersMessage = null, usersSavedAt = null) }
        } catch (e: ApiException) {
            if (e.code != ApiException.OFFLINE) return _state.update { it.copy(usersMessage = e.error.message) }
            val saved = store?.load(USERS_KEY, USERS)
            _state.update {
                if (saved != null) it.copy(users = saved.value, usersSavedAt = saved.savedAt, usersMessage = null, offline = true)
                else it.copy(offline = true, usersMessage = "Sin conexión: los usuarios se cargarán solos al volver la señal.")
            }
        }
    }

    companion object {
        const val REQUESTS_KEY = "admin-requests"
        const val USERS_KEY = "admin-users"
        private val REQUESTS = ListSerializer(PendingMembership.serializer())
        private val USERS = ListSerializer(AdminUser.serializer())
        const val APPROVE_OFFLINE_MESSAGE =
            "Sin conexión: aprobar necesita señal para confirmar que la solicitud sigue pendiente. Inténtalo al volver la señal."
        const val ROLE_OFFLINE_MESSAGE =
            "Sin conexión: cambiar un rol necesita señal. Inténtalo al volver la señal."
    }
}

/** A user can request several establishments, so the pair identifies the request. */
val PendingMembership.key: String get() = "$establishmentId:$userId"

fun roleName(role: String?): String = when (role) {
    "admin" -> "Administrador"
    "restaurant" -> "Restaurante"
    else -> "Estudiante"
}
