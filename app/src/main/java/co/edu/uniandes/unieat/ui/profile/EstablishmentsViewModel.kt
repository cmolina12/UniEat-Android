package co.edu.uniandes.unieat.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.uniandes.unieat.core.decision.Coordinate
import co.edu.uniandes.unieat.core.model.EstablishmentMembership
import co.edu.uniandes.unieat.core.model.EstablishmentRequestBody
import co.edu.uniandes.unieat.data.location.AddressGeocoder
import co.edu.uniandes.unieat.data.location.LocationRepository
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.EstablishmentRepository
import co.edu.uniandes.unieat.data.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

data class EstablishmentRequestForm(
    val name: String = "",
    val area: String = "Centro",
    val address: String = "",
    val entranceDescription: String = "",
    val photoUrl: String = "",
    val paymentMethods: Set<String> = setOf("Efectivo"),
    val pin: Coordinate? = null,
    /** A pin found from the address is dropped when the address changes; a GPS or hand-placed one stays. */
    val pinFromAddress: Boolean = false,
) {
    val photoUrlValid: Boolean get() = isValidPhotoUrl(photoUrl)

    val canSubmit: Boolean
        get() = name.isNotBlank() && address.isNotBlank() && paymentMethods.isNotEmpty() && photoUrlValid
}

data class EstablishmentsUiState(
    val memberships: List<EstablishmentMembership> = emptyList(),
    val role: String? = null,
    val formOpen: Boolean = false,
    val form: EstablishmentRequestForm = EstablishmentRequestForm(),
    val working: Boolean = false,
    val geocoding: Boolean = false,
    val locating: Boolean = false,
    val pinMessage: String? = null,
    val message: String? = null,
)

/**
 * "Mis establecimientos" in Perfil, same as iOS EstablishmentManagementView: the state of each
 * establishment and a form to request a new one with its pin. [onAccountChanged] refreshes the
 * app's tabs after the account is reloaded (an approval turns a student into a restaurant).
 */
class EstablishmentsViewModel(
    private val profiles: ProfileRepository,
    private val establishments: EstablishmentRepository,
    private val location: LocationRepository,
    private val geocoder: AddressGeocoder,
    private val onAccountChanged: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(EstablishmentsUiState())
    val state: StateFlow<EstablishmentsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reloadAccount() }
    }

    /** "Actualizar estado": asks again whether an admin approved the requests. */
    fun refreshStatus() {
        if (_state.value.working) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            val reloaded = reloadAccount()
            onAccountChanged()
            _state.update {
                it.copy(
                    working = false,
                    message = when {
                        !reloaded -> it.message
                        it.role == "restaurant" -> "Tu cuenta ya puede publicar menús."
                        else -> "Estado actualizado. Las solicitudes pendientes requieren aprobación."
                    },
                )
            }
        }
    }

    fun toggleForm() = _state.update { it.copy(formOpen = !it.formOpen) }

    fun updateForm(change: (EstablishmentRequestForm) -> EstablishmentRequestForm) =
        _state.update { it.copy(form = change(it.form)) }

    fun setAddress(address: String) = _state.update { state ->
        val form = state.form.copy(address = address)
        if (form.pinFromAddress) {
            state.copy(form = form.copy(pin = null, pinFromAddress = false), pinMessage = null)
        } else {
            state.copy(form = form)
        }
    }

    fun togglePayment(method: String) = updateForm { form ->
        form.copy(paymentMethods = if (method in form.paymentMethods) form.paymentMethods - method else form.paymentMethods + method)
    }

    /** "Buscar dirección": approximate pin from the address; the owner checks it on the map. */
    fun findAddress() {
        val address = _state.value.form.address
        if (address.isBlank() || _state.value.geocoding) return
        _state.update { it.copy(geocoding = true) }
        viewModelScope.launch {
            val found = geocoder.find(address)
            _state.update {
                if (found != null) {
                    it.copy(
                        geocoding = false,
                        form = it.form.copy(pin = found, pinFromAddress = true),
                        pinMessage = "Ubicación aproximada según la dirección. Revísala en el mapa.",
                    )
                } else {
                    it.copy(
                        geocoding = false,
                        pinMessage = "No encontramos esa dirección. Usa tu ubicación estando en el local o mueve el pin.",
                    )
                }
            }
        }
    }

    /** "Usar mi ubicación": called once the location permission is granted. */
    fun useMyLocation() {
        if (_state.value.locating) return
        _state.update { it.copy(locating = true, pinMessage = null) }
        viewModelScope.launch {
            val here = runCatching {
                withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) { location.locationUpdates(precise = true).firstOrNull() }
            }.getOrNull()
            _state.update {
                if (here != null) {
                    val accuracy = here.accuracyMeters?.let { meters -> " (±${meters.toInt()} m)" }.orEmpty()
                    it.copy(
                        locating = false,
                        form = it.form.copy(pin = here.coordinate, pinFromAddress = false),
                        pinMessage = "Pin tomado de tu ubicación actual$accuracy. Úsalo estando en el local.",
                    )
                } else {
                    it.copy(locating = false, pinMessage = "No pudimos obtener tu ubicación. Revisa que el GPS esté activo.")
                }
            }
        }
    }

    fun locationDenied() = _state.update {
        it.copy(pinMessage = "Sin permiso de ubicación. Busca la dirección o toca el mapa para poner el pin.")
    }

    fun movePin(coordinate: Coordinate) = _state.update {
        it.copy(form = it.form.copy(pin = coordinate, pinFromAddress = false), pinMessage = "Pin movido manualmente.")
    }

    fun submit() {
        val form = _state.value.form
        if (!form.canSubmit || _state.value.working) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            // Without a pin the address is tried; if that fails too, the request goes without coordinates.
            val pin = form.pin ?: geocoder.find(form.address)
            val body = EstablishmentRequestBody(
                name = form.name.trim(),
                area = form.area,
                address = form.address.trim(),
                entranceDescription = form.entranceDescription.trim(),
                paymentMethods = PAYMENT_METHODS.filter { it in form.paymentMethods },
                photoUrl = form.photoUrl.trim().ifEmpty { null },
                latitude = pin?.latitude,
                longitude = pin?.longitude,
            )
            try {
                establishments.request(body)
                // The request already exists: if reloading fails it must not look like the send failed.
                reloadAccount()
                _state.update {
                    it.copy(
                        working = false,
                        formOpen = false,
                        form = EstablishmentRequestForm(area = form.area, paymentMethods = form.paymentMethods),
                        pinMessage = null,
                        message = "Solicitud enviada. Podrás publicar cuando un administrador la apruebe.",
                    )
                }
            } catch (e: ApiException) {
                _state.update { it.copy(working = false, message = e.error.message) }
            }
        }
    }

    /** GET /me: role and memberships. Returns false (with a message) when it fails. */
    private suspend fun reloadAccount(): Boolean = try {
        val me = profiles.me()
        _state.update { it.copy(memberships = me.establishments, role = me.role) }
        true
    } catch (e: ApiException) {
        _state.update { it.copy(message = e.error.message) }
        false
    }

    companion object {
        val AREAS = listOf("Centro", "Norte", "Sur", "Fuera del campus")
        val PAYMENT_METHODS = listOf("Nequi", "Daviplata", "Efectivo", "Tarjeta")
        private const val LOCATION_TIMEOUT_MILLIS = 15_000L
    }
}

/** Same rule as iOS and api-v1: empty, or an HTTPS link of at most 500 characters without user or password. */
internal fun isValidPhotoUrl(raw: String): Boolean {
    val value = raw.trim()
    if (value.isEmpty()) return true
    if (value.length > 500) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
}
