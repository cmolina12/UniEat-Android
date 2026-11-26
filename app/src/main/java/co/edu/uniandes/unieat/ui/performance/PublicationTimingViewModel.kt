package co.edu.uniandes.unieat.ui.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.uniandes.unieat.core.model.PublicationTimingSnapshot
import co.edu.uniandes.unieat.data.local.OfflineStore
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.IOException

data class PublicationTimingState(
    val snapshot: PublicationTimingSnapshot? = null,
    val online: Boolean = false,
    val refreshing: Boolean = true,
    val showingCopy: Boolean = false,
    val error: String? = null,
    val sessionExpired: Boolean = false,
)

class PublicationTimingViewModel(
    private val userId: String,
    private val currentUserId: () -> String?,
    private val fetch: suspend () -> PublicationTimingSnapshot,
    online: Flow<Boolean>,
    private val store: OfflineStore?,
) : ViewModel() {
    private val _state = MutableStateFlow(PublicationTimingState())
    val state = _state.asStateFlow()
    private val diskKey = "publication-timing-$userId"
    private val serializer = PublicationTimingSnapshot.serializer()
    private var request: Job? = null
    private var accessDenied = false

    init {
        viewModelScope.launch {
            online.distinctUntilChanged().collect { connected ->
                _state.value = _state.value.copy(online = connected)
                reload()
            }
        }
    }

    fun reload() {
        request?.cancel()
        request = viewModelScope.launch {
            if (currentUserId() != userId) return@launch
            val connected = _state.value.online
            _state.value = _state.value.copy(refreshing = true, error = null)
            val cached = _state.value.snapshot ?: if (accessDenied) null else store?.load(diskKey, serializer)?.value
            if (currentUserId() != userId) return@launch
            _state.value = _state.value.copy(snapshot = cached, showingCopy = cached != null, refreshing = connected)
            if (!connected) return@launch

            try {
                val snapshot = fetch()
                currentCoroutineContext().ensureActive()
                if (currentUserId() != userId) return@launch
                accessDenied = false
                _state.value = PublicationTimingState(snapshot = snapshot, online = true, refreshing = false)
                try {
                    store?.save(diskKey, serializer, snapshot)
                } catch (_: IOException) {
                    _state.value = _state.value.copy(error = "No se pudo guardar una copia para usar sin conexión.")
                }
            } catch (error: ApiException) {
                currentCoroutineContext().ensureActive()
                if (currentUserId() != userId) return@launch
                when (error.code) {
                    ApiException.AUTH_REQUIRED, ApiException.FORBIDDEN -> {
                        accessDenied = true
                        _state.value = PublicationTimingState(online = true, refreshing = false,
                            error = error.message, sessionExpired = error.code == ApiException.AUTH_REQUIRED)
                        store?.remove(diskKey)
                    }
                    else -> _state.value = _state.value.copy(refreshing = false, error = error.message)
                }
            }
        }
    }
}
