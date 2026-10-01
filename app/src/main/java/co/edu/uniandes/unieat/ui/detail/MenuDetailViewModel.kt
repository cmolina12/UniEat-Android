package co.edu.uniandes.unieat.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.decision.Coordinate
import co.edu.uniandes.unieat.core.decision.LocationGuidance
import co.edu.uniandes.unieat.core.decision.Proximity
import co.edu.uniandes.unieat.core.decision.locationGuidance
import co.edu.uniandes.unieat.core.decision.proximity
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.ReportBody
import co.edu.uniandes.unieat.core.model.ReportKind
import co.edu.uniandes.unieat.data.analytics.EventKind
import co.edu.uniandes.unieat.data.analytics.EventTracker
import co.edu.uniandes.unieat.data.analytics.track
import co.edu.uniandes.unieat.data.location.LocationRepository
import co.edu.uniandes.unieat.data.location.UserLocation
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.ReportRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** Report sheet submission (POST /reports). The form fields themselves are UI state in the sheet. */
sealed interface ReportSubmission {
    data object Idle : ReportSubmission
    data object Sending : ReportSubmission
    /** [message] comes from the server ("…el menú oficial no cambia."). */
    data class Sent(val message: String, val pending: Boolean) : ReportSubmission
    data class Failed(val message: String) : ReportSubmission
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

/** What the distance depends on from the menu: only the pin, so refreshing reports does not restart the GPS. */
private sealed interface PinState {
    data object NotLoaded : PinState
    data object Missing : PinState
    data class At(val coordinate: Coordinate) : PinState
}

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
    private val reportRepository: ReportRepository,
    private val deviceClock: () -> Instant = Instant::now,
) : ViewModel() {

    private val _state = MutableStateFlow<MenuDetailUiState>(MenuDetailUiState.Loading)
    val state: StateFlow<MenuDetailUiState> = _state.asStateFlow()

    private val access = MutableStateFlow(LocationAccess())

    private val pinState = _state
        .map { state ->
            when (state) {
                is MenuDetailUiState.Content -> state.location.pin?.let(PinState::At) ?: PinState.Missing
                else -> PinState.NotLoaded
            }
        }
        .distinctUntilChanged()

    /**
     * Re-evaluated whenever the menu or the location context changes. WhileSubscribed stops the GPS
     * 5 s after the screen stops collecting (background), and restarts it when it comes back.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val distance: StateFlow<DistanceStatus> = combine(pinState, access, locationRepository.locationEnabled(), ::Triple)
        .flatMapLatest { (pin, access, enabled) -> distanceFlow(pin, access, enabled) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DistanceStatus.Idle)

    private val _arrival = MutableStateFlow<ArrivalAnswer?>(null)
    val arrival: StateFlow<ArrivalAnswer?> = _arrival.asStateFlow()

    private val _report = MutableStateFlow<ReportSubmission>(ReportSubmission.Idle)
    val report: StateFlow<ReportSubmission> = _report.asStateFlow()

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
        if (arrived) recordArrival("arrival_prompt") else _arrival.value = ArrivalAnswer.DISMISSED
    }

    /** One arrival event per visit, whether it came from the prompt or from the report sheet. */
    private fun recordArrival(source: String) {
        if (_arrival.value == ArrivalAnswer.CONFIRMED) return
        _arrival.value = ArrivalAnswer.CONFIRMED
        val menu = (_state.value as? MenuDetailUiState.Content)?.menu ?: return
        eventTracker.track(EventKind.ARRIVAL, menu, SCREEN, source = source)
    }

    /** Clears the previous result when the sheet opens again (unless a send is still running). */
    fun onReportSheetOpened() {
        if (_report.value !is ReportSubmission.Sending) _report.value = ReportSubmission.Idle
    }

    /**
     * POST /reports for the version on screen. Reports are not queued offline like analytics: the
     * student needs to know whether it was received, so a failure is shown and they can retry.
     */
    fun submitReport(kind: ReportKind, note: String, observedWaitMinutes: Int?) {
        val menu = (_state.value as? MenuDetailUiState.Content)?.menu ?: return
        if (_report.value is ReportSubmission.Sending) return // double tap
        _report.value = ReportSubmission.Sending
        viewModelScope.launch {
            _report.value = try {
                val response = reportRepository.submit(
                    ReportBody(
                        publicationId = menu.id,
                        version = menu.version,
                        kind = kind,
                        note = note.trim().ifEmpty { null },
                        observedWaitMinutes = observedWaitMinutes.takeIf { kind == ReportKind.LONG_LINE },
                    ),
                )
                if (kind == ReportKind.ARRIVAL) recordArrival("report_sheet")
                val pending = response.status == "pending"
                // A pending discrepancy changes what others see (e.g. the BQ-05 location warning).
                if (pending) refreshQuietly()
                ReportSubmission.Sent(response.message.ifBlank { "Gracias. Tu reporte quedó registrado." }, pending)
            } catch (e: ApiException) {
                ReportSubmission.Failed(
                    if (e.code == ApiException.OFFLINE) "Sin conexión: tu reporte no se envió. Inténtalo de nuevo cuando tengas señal."
                    else e.error.message,
                )
            }
        }
    }

    /** Reloads without the Loading state; on failure the current content simply stays. */
    private suspend fun refreshQuietly() {
        try {
            val response = repository.menu(menuId)
            _state.value = MenuDetailUiState.Content(response.menu, Duration.between(deviceClock(), response.serverNow))
        } catch (_: ApiException) {
            // Keep showing what we have; the report itself was already accepted.
        }
    }

    /** "Elegir este menú" (same event as iOS). */
    fun onSelect() {
        val menu = (_state.value as? MenuDetailUiState.Content)?.menu ?: return
        eventTracker.track(EventKind.SELECTION, menu, SCREEN)
    }

    private fun distanceFlow(pinState: PinState, access: LocationAccess, enabled: Boolean): Flow<DistanceStatus> {
        if (pinState == PinState.NotLoaded || !access.checked) return flowOf(DistanceStatus.Idle)
        val pin = (pinState as? PinState.At)?.coordinate ?: return flowOf(DistanceStatus.NoRestaurantPin)
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
                    app.container.reportRepository,
                )
            }
        }
    }
}
