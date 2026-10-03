package co.edu.uniandes.unieat.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.edu.uniandes.unieat.DemoFixture
import co.edu.uniandes.unieat.DevDataSource
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

    data class Content(val menu: DailyMenu, val clockOffset: Duration) : MenuDetailUiState {
        val location: LocationGuidance = locationGuidance(menu)
    }

    data class Gone(val message: String) : MenuDetailUiState

    data object SessionExpired : MenuDetailUiState

    data class Error(val message: String, val code: String) : MenuDetailUiState
}

sealed interface ReportSubmission {
    data object Idle : ReportSubmission
    data object Sending : ReportSubmission
    data class Sent(val message: String, val pending: Boolean) : ReportSubmission
    data class Failed(val message: String) : ReportSubmission
}

enum class LocationPermission { PRECISE, APPROXIMATE, NONE }

sealed interface DistanceStatus {
    data object Idle : DistanceStatus
    data object NoRestaurantPin : DistanceStatus
    data object PermissionNeeded : DistanceStatus
    data class PermissionDenied(val permanently: Boolean) : DistanceStatus
    data object LocationOff : DistanceStatus
    data object Searching : DistanceStatus
    data object Unavailable : DistanceStatus
    data class Known(
        val proximity: Proximity,
        val approximate: Boolean,
        val user: Coordinate,
        val accuracyMeters: Float?,
    ) : DistanceStatus
}

enum class ArrivalAnswer { CONFIRMED, DISMISSED }

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

class MenuDetailViewModel(
    private val menuId: String,
    private val repository: MenuRepository,
    private val locationRepository: LocationRepository,
    private val eventTracker: EventTracker,
    private val reportRepository: ReportRepository,
    val isDemo: Boolean = false,
    val fixtures: List<DemoFixture> = emptyList(),
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val distance: StateFlow<DistanceStatus> = combine(pinState, access, locationRepository.locationEnabled(), ::Triple)
        .flatMapLatest { (pin, access, enabled) -> distanceFlow(pin, access, enabled) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DistanceStatus.Idle)

    private val _arrival = MutableStateFlow<ArrivalAnswer?>(null)
    val arrival: StateFlow<ArrivalAnswer?> = _arrival.asStateFlow()

    private val _report = MutableStateFlow<ReportSubmission>(ReportSubmission.Idle)
    val report: StateFlow<ReportSubmission> = _report.asStateFlow()

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
                    ApiException.AUTH_REQUIRED -> MenuDetailUiState.SessionExpired
                    ApiException.OFFLINE -> MenuDetailUiState.Error(OFFLINE_MESSAGE, e.code)
                    else -> MenuDetailUiState.Error(e.error.message, e.code)
                }
            }
        }
    }

    fun onLocationPermissionChecked(permission: LocationPermission) = access.update {
        val granted = permission != LocationPermission.NONE
        it.copy(
            permission = permission,
            checked = true,
            declined = it.declined && !granted,
            permanentlyDenied = it.permanentlyDenied && !granted,
        )
    }

    fun onLocationPermissionResult(permission: LocationPermission, canAskAgain: Boolean) = access.update {
        val denied = permission == LocationPermission.NONE
        it.copy(
            permission = permission,
            checked = true,
            declined = denied,
            permanentlyDenied = denied && !canAskAgain,
        )
    }

    fun onLocationPromptDismissed() = access.update { it.copy(declined = true) }

    fun onArrivalAnswered(arrived: Boolean) {
        if (_arrival.value != null) return
        if (arrived) recordArrival("arrival_prompt") else _arrival.value = ArrivalAnswer.DISMISSED
    }

    private fun recordArrival(source: String) {
        if (_arrival.value == ArrivalAnswer.CONFIRMED) return
        _arrival.value = ArrivalAnswer.CONFIRMED
        val menu = (_state.value as? MenuDetailUiState.Content)?.menu ?: return
        eventTracker.track(EventKind.ARRIVAL, menu, SCREEN, source = source)
    }

    fun onReportSheetOpened() {
        if (_report.value !is ReportSubmission.Sending) _report.value = ReportSubmission.Idle
    }

    fun submitReport(kind: ReportKind, note: String, observedWaitMinutes: Int?) {
        val menu = (_state.value as? MenuDetailUiState.Content)?.menu ?: return
        if (_report.value is ReportSubmission.Sending) return
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

    private suspend fun refreshQuietly() {
        try {
            val response = repository.menu(menuId)
            _state.value = MenuDetailUiState.Content(response.menu, Duration.between(deviceClock(), response.serverNow))
        } catch (_: ApiException) {
        }
    }

    fun onOpenMaps(source: String) {
        val menu = (_state.value as? MenuDetailUiState.Content)?.menu ?: return
        eventTracker.track(EventKind.LOCATION_OPEN, menu, SCREEN, source = source)
    }

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
                        DistanceStatus.Known(
                            proximity(it.coordinate, it.accuracyMeters, pin),
                            approximate = !precise,
                            user = it.coordinate,
                            accuracyMeters = it.accuracyMeters,
                        )
                    }
                    .onStart { emit(DistanceStatus.Searching) }
                    .catch { emit(DistanceStatus.Unavailable) }
            }
        }
    }

    companion object {
        private const val SCREEN = "detail"
        internal const val OFFLINE_MESSAGE = "Sin conexión con el servidor. Revisa tu señal e inténtalo de nuevo."

        fun factory(menuId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                val demo = app.container.usesFakeData
                MenuDetailViewModel(
                    menuId,
                    app.container.menuRepository,
                    app.container.locationRepository,
                    app.container.eventTracker,
                    app.container.reportRepository,
                    isDemo = demo,
                    fixtures = if (demo) DevDataSource.fixtures else DevDataSource.seedFixtures,
                )
            }
        }
    }
}
