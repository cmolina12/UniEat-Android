package co.edu.uniandes.unieat.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.decision.LocationGuidance
import co.edu.uniandes.unieat.core.decision.Proximity
import co.edu.uniandes.unieat.core.decision.proximity
import co.edu.uniandes.unieat.core.decision.locationGuidance
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.data.analytics.EventKind
import co.edu.uniandes.unieat.data.analytics.EventTracker
import co.edu.uniandes.unieat.data.analytics.track
import co.edu.uniandes.unieat.data.location.LocationRepository
import co.edu.uniandes.unieat.data.location.UserLocation
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

sealed interface MenuDetailUiState {
    data object Loading : MenuDetailUiState

    /**
     * [clockOffset] = server time − device time when the menu was fetched. The screen adds it to the
     * device clock so "vigente/vencido" follows the server, as the contract asks (`serverNow`).
     */
    data class Content(val menu: DailyMenu, val clockOffset: Duration) : MenuDetailUiState {
        /** BQ-05 answer for this menu version, derived once per load. */
        val location: LocationGuidance = locationGuidance(menu)
    }

    /** 410 GONE: the menu closed or expired. Not retryable. */
    data class Gone(val message: String) : MenuDetailUiState

    data class Error(val message: String, val code: String) : MenuDetailUiState
}

/** Runtime location permission as the user granted it (Android 12+ lets them pick "approximate"). */
enum class LocationPermission { PRECISE, APPROXIMATE, NONE }

/** Context-aware distance line: what the card can say given permission, GPS state and the pin. */
sealed interface DistanceStatus {
    /** Not evaluated yet (menu loading or permission not checked). Renders nothing. */
    data object Idle : DistanceStatus
    /** The restaurant has no pin; the card already says "Ubicación no confirmada". */
    data object NoRestaurantPin : DistanceStatus
    /** Explain why before showing the system dialog. */
    data object PermissionNeeded : DistanceStatus
    /** The user said no. [permanently] = Android will not show the dialog again; offer settings. */
    data class PermissionDenied(val permanently: Boolean) : DistanceStatus
    /** Location is turned off in system settings. */
    data object LocationOff : DistanceStatus
    data object Searching : DistanceStatus
    data object Unavailable : DistanceStatus
    data class Known(val proximity: Proximity, val approximate: Boolean) : DistanceStatus
}

enum class ArrivalAnswer { CONFIRMED, DISMISSED }

private data class LocationAccess(
    val permission: LocationPermission = LocationPermission.NONE,
    val checked: Boolean = false,
    val declined: Boolean = false,
    val permanentlyDenied: Boolean = false,
)

/**
 * Loads GET /menus/:id through [MenuRepository] and maps the outcome to [MenuDetailUiState].
 * Also derives the walking distance from [LocationRepository] while the screen is visible.
 */
class MenuDetailViewModel(
    private val menuId: String,
    private val repository: MenuRepository,
    private val locationRepository: LocationRepository,
    private val eventTracker: EventTracker,
    private val deviceClock: () -> Instant = Instant::now,
) : ViewModel() {

    private val _state = MutableStateFlow<MenuDetailUiState>(MenuDetailUiState.Loading)
    val state: StateFlow<MenuDetailUiState> = _state.asStateFlow()

    private val access = MutableStateFlow(LocationAccess())

    /**
     * Re-evaluated whenever the menu or the location context changes. WhileSubscribed stops the GPS
     * 5 s after the screen stops collecting (background), and restarts it when it comes back.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val distance: StateFlow<DistanceStatus> = combine(_state, access, locationRepository.locationEnabled(), ::Triple)
        .flatMapLatest { (state, access, enabled) -> distanceFlow(state, access, enabled) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DistanceStatus.Idle)

    private val _arrival = MutableStateFlow<ArrivalAnswer?>(null)
    val arrival: StateFlow<ArrivalAnswer?> = _arrival.asStateFlow()

    /** detail_open is sent once per opening, not again on retry or rotation (the ViewModel survives it). */
    private var openTracked = false

    init {
        load()
    }

    fun load() {
        _state.value = MenuDetailUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val response = repository.menu(menuId)
                if (!openTracked) {
                    openTracked = true
                    eventTracker.track(EventKind.DETAIL_OPEN, response.menu, SCREEN)
                }
                MenuDetailUiState.Content(response.menu, Duration.between(deviceClock(), response.serverNow))
            } catch (e: ApiException) {
                when (e.code) {
                    ApiException.GONE -> MenuDetailUiState.Gone(e.error.message)
                    else -> MenuDetailUiState.Error(e.error.message, e.code)
                }
            }
        }
    }

    /** Current permission, checked by the screen on every resume (the user may change it in settings). */
    fun onLocationPermissionChecked(permission: LocationPermission) = access.update {
        val granted = permission != LocationPermission.NONE
        it.copy(
            permission = permission,
            checked = true,
            declined = it.declined && !granted,
            permanentlyDenied = it.permanentlyDenied && !granted,
        )
    }

    /** Outcome of the system dialog. [canAskAgain] = shouldShowRequestPermissionRationale after a denial. */
    fun onLocationPermissionResult(permission: LocationPermission, canAskAgain: Boolean) = access.update {
        val denied = permission == LocationPermission.NONE
        it.copy(
            permission = permission,
            checked = true,
            declined = denied,
            permanentlyDenied = denied && !canAskAgain,
        )
    }

    /** "Ahora no" on our explanation: never show the system dialog. */
    fun onLocationPromptDismissed() = access.update { it.copy(declined = true) }

    fun onArrivalAnswered(arrived: Boolean) {
        if (_arrival.value != null) return // one answer per visit; a double tap must not send two events
        _arrival.value = if (arrived) ArrivalAnswer.CONFIRMED else ArrivalAnswer.DISMISSED
        val menu = (_state.value as? MenuDetailUiState.Content)?.menu ?: return
        if (arrived) eventTracker.track(EventKind.ARRIVAL, menu, SCREEN, source = "arrival_prompt")
    }

    /** "Elegir este menú" (same event as iOS). */
    fun onSelect() {
        val menu = (_state.value as? MenuDetailUiState.Content)?.menu ?: return
        eventTracker.track(EventKind.SELECTION, menu, SCREEN)
    }

    private fun distanceFlow(state: MenuDetailUiState, access: LocationAccess, enabled: Boolean): Flow<DistanceStatus> {
        if (state !is MenuDetailUiState.Content || !access.checked) return flowOf(DistanceStatus.Idle)
        val pin = state.location.pin ?: return flowOf(DistanceStatus.NoRestaurantPin)
        return when {
            access.permission == LocationPermission.NONE ->
                flowOf(if (access.declined) DistanceStatus.PermissionDenied(access.permanentlyDenied) else DistanceStatus.PermissionNeeded)
            !enabled -> flowOf(DistanceStatus.LocationOff)
            else -> {
                val precise = access.permission == LocationPermission.PRECISE
                locationRepository.locationUpdates(precise)
                    .map<UserLocation, DistanceStatus> {
                        DistanceStatus.Known(proximity(it.coordinate, it.accuracyMeters, pin), approximate = !precise)
                    }
                    .onStart { emit(DistanceStatus.Searching) }
                    .catch { emit(DistanceStatus.Unavailable) }
            }
        }
    }

    companion object {
        private const val SCREEN = "detail"

        fun factory(menuId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                MenuDetailViewModel(
                    menuId,
                    app.container.menuRepository,
                    app.container.locationRepository,
                    app.container.eventTracker,
                )
            }
        }
    }
}
