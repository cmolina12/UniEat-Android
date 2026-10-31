package co.edu.uniandes.unieat.ui.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.LocationCompletenessSnapshot
import co.edu.uniandes.unieat.core.model.LocationGuidanceSnapshot
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.pilot.CachePilotReport
import co.edu.uniandes.unieat.data.pilot.CachePilotRepository
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.telemetry.FeedLoadReport
import co.edu.uniandes.unieat.data.telemetry.FeedLoadRepository
import androidx.collection.SparseArrayCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import co.edu.uniandes.unieat.data.local.PerformancePreferences
import co.edu.uniandes.unieat.data.local.PerformanceSnapshots
import co.edu.uniandes.unieat.data.local.SavedPerformance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** What the dashboard can show. One state at a time, so the UI is a simple `when`. */
sealed interface PerformanceUiState {
    data object Loading : PerformanceUiState

    data class Content(
        val summary: PerformanceSummary,
        val feedLoadReport: FeedLoadReport? = null,
        val ownEstablishmentsOnly: Boolean = false,
        val locationGuidance: LocationGuidanceSnapshot? = null,
        val locationCompleteness: LocationCompletenessSnapshot? = null,
        /** When the shown copy was saved (offline or while refreshing). */
        val savedAt: Instant? = null,
        /** Saved copy shown while the request is still running (slow signal). */
        val refreshing: Boolean = false,
        /** BQ-13 (Samuel): cache pilot comparison; admins only, null if it could not be loaded. */
        val cachePilot: CachePilotReport? = null,
        val lineCongestion: List<CongestionRow>? = null,
    ) : PerformanceUiState

    /** FORBIDDEN: metrics belong to restaurant or admin accounts. */
    data class Restricted(val message: String) : PerformanceUiState

    /** AUTH_REQUIRED: the screen navigates back to the login. */
    data object SessionExpired : PerformanceUiState

    data class Error(val message: String, val code: String) : PerformanceUiState
}

/**
 * The numbers come from the backend's event pipeline; nothing is computed on the client.
 */
class PerformanceViewModel(
    private val repository: AnalyticsRepository,
    /** BQ-01 (Samuel): its card is extra; if it fails, the rest of the dashboard still loads. */
    private val feedLoads: FeedLoadRepository? = null,
    private val role: String? = null,
    private val showLocationGuidance: Boolean = false,
    /** Tests pass their own dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** DataStore: remembers 7 or 28 days. */
    private val preferences: PerformancePreferences? = null,
    /** Last result per role and period, for offline. */
    private val snapshots: PerformanceSnapshots? = null,
    /** Offline: the numbers get their saved time. Back online: reload. */
    online: Flow<Boolean>? = null,
    private val clock: () -> Instant = Instant::now,
    /** BQ-13 (Samuel): optional card, like BQ-01; its failure never hides the rest of the dashboard. */
    private val cachePilot: CachePilotRepository? = null,
    private val menus: MenuRepository? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<PerformanceUiState>(PerformanceUiState.Loading)
    val state: StateFlow<PerformanceUiState> = _state.asStateFlow()

    // 7 or 28 days, the two periods the backend supports
    private val _days = MutableStateFlow(7)
    val days: StateFlow<Int> = _days.asStateFlow()

    /**
     * Last result per period (7 or 28), so switching back is instant.
     * SparseArrayCompat: int keys without boxing, sorted int[] + binary search.
     * Capacity 2 = the two periods the backend has, it never grows.
     */
    private val byPeriod = SparseArrayCompat<PerformanceUiState.Content>(PERIODS)

    /** Cancelled when the period changes, so an old answer can't overwrite the new one. */
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            // read the saved period before the first request
            preferences?.let { _days.value = it.days.first() }
            load()
        }
        if (online != null) viewModelScope.launch {
            online.collect { isOnline ->
                val current = _state.value
                if (!isOnline) {
                    // they were saved to disk when they arrived, that copy has the time
                    if (current is PerformanceUiState.Content && current.savedAt == null) {
                        val saved = savedCopy(_days.value)
                        if (saved != null && _state.value === current) _state.value = saved
                    }
                    return@collect
                }
                val showingSaved = current is PerformanceUiState.Content && current.savedAt != null
                val offlineError = current is PerformanceUiState.Error && current.code == ApiException.OFFLINE
                if (showingSaved || offlineError) load()
            }
        }
    }

    /**
     * Main -> withContext(IO) -> 4 async requests at the same time.
     * We wait for the slowest one, not the sum. The result goes back to Main.
     */
    fun load() {
        val days = _days.value
        _state.value = byPeriod[days] ?: PerformanceUiState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // nothing in memory: show the disk copy while the request runs
            if (_state.value == PerformanceUiState.Loading) {
                savedCopy(days)?.let { _state.value = it.copy(refreshing = true) }
            }
            _state.value = try {
                val restaurant = role == ROLE_RESTAURANT
                val content = withContext(io) {
                    val summary = async { if (restaurant) repository.restaurantPerformance(days) else repository.performance(days) }
                    val feedLoad = async { if (role == ROLE_ADMIN) feedLoadReport() else null }
                    val guidance = async { locationGuidance(days, restaurant) }
                    val completeness = async { locationCompleteness(days) }
                    // BQ-13 (Samuel): a fifth child coroutine on IO, in parallel with the other four.
                    val pilot = async { if (role == ROLE_ADMIN) cachePilotReport(days) else null }
                    val congestion = async {
                        if (role == ROLE_ADMIN && menus != null) {
                            runCatching {
                                val feed = menus.feedForDashboard()
                                lineCongestion(feed.menus, feed.serverNow)
                            }.getOrNull()
                        } else {
                            null
                        }
                    }
                    // Only the summary can fail the screen; the BQ cards are optional (null if they fail).
                    PerformanceUiState.Content(
                        summary.await(), feedLoad.await(), restaurant, guidance.await(), completeness.await(),
                        cachePilot = pilot.await(),
                        lineCongestion = congestion.await(),
                    )
                }
                byPeriod.put(days, content)
                save(content, days)
                content
            } catch (e: ApiException) {
                when (e.code) {
                    ApiException.FORBIDDEN -> PerformanceUiState.Restricted(e.error.message)
                    ApiException.AUTH_REQUIRED -> PerformanceUiState.SessionExpired
                    ApiException.OFFLINE -> savedCopy(days) ?: PerformanceUiState.Error(e.error.message, e.code)
                    else -> PerformanceUiState.Error(e.error.message, e.code)
                }
            }
        }
    }

    /** Saved for offline. If saving fails we just skip it. */
    private suspend fun save(content: PerformanceUiState.Content, days: Int) {
        val store = snapshots ?: return
        runCatching {
            store.save(
                SavedPerformance(
                    role = roleKey, days = days, savedAt = clock(),
                    summary = content.summary, feedLoadReport = content.feedLoadReport,
                    ownEstablishmentsOnly = content.ownEstablishmentsOnly,
                    locationGuidance = content.locationGuidance, locationCompleteness = content.locationCompleteness,
                    cachePilot = content.cachePilot,
                ),
            )
        }
    }

    private suspend fun savedCopy(days: Int): PerformanceUiState.Content? {
        val saved = snapshots?.load(roleKey, days) ?: return null
        return PerformanceUiState.Content(
            saved.summary, saved.feedLoadReport, saved.ownEstablishmentsOnly,
            saved.locationGuidance, saved.locationCompleteness, savedAt = saved.savedAt,
            cachePilot = saved.cachePilot,
        )
    }

    private val roleKey: String get() = role ?: "none"

    /** BQ-01: admin only (the endpoint is admin-only) and always 7 days. */
    private suspend fun feedLoadReport(): FeedLoadReport? =
        feedLoads?.let { runCatching { it.summary(BQ01_DAYS) }.getOrNull() }

    private suspend fun locationGuidance(days: Int, restaurant: Boolean): LocationGuidanceSnapshot? {
        if (!showLocationGuidance) return null
        return runCatching {
            if (restaurant) repository.restaurantLocationGuidance(days) else repository.locationGuidance(days)
        }.getOrNull()
    }

    /**
     * BQ-13 (Samuel): same period as the rest of the dashboard (7 or 28 days). Admin-only, like BQ-01: it is
     * a decision about the app (keep or revise the cache), not information for one restaurant.
     */
    private suspend fun cachePilotReport(days: Int): CachePilotReport? =
        cachePilot?.let { runCatching { it.summary(days) }.getOrNull() }

    /** BQ-10: same roles as BQ-05. If it fails the rest still shows. */
    private suspend fun locationCompleteness(days: Int): LocationCompletenessSnapshot? {
        if (!showLocationGuidance) return null
        return runCatching { repository.locationCompleteness(days) }.getOrNull()
    }

    /** Switch between 7 and 28 days; picking the same period again does nothing. */
    fun selectDays(days: Int) {
        if (_days.value == days) return
        _days.value = days
        preferences?.let { viewModelScope.launch { it.setDays(days) } }
        load()
    }

    companion object {
        private const val BQ01_DAYS = 7
        // 7 and 28 days
        private const val PERIODS = 2
        private const val ROLE_RESTAURANT = "restaurant"
        private const val ROLE_ADMIN = "admin"

        fun factory(role: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                PerformanceViewModel(
                    app.container.analyticsRepository,
                    app.container.feedLoadRepository,
                    role = role,
                    showLocationGuidance = role == ROLE_ADMIN || role == ROLE_RESTAURANT || app.container.usesFakeData,
                    preferences = app.container.performancePreferences,
                    snapshots = app.container.performanceSnapshots,
                    online = app.container.connectivity.online,
                    cachePilot = app.container.cachePilotRepository,
                    menus = app.container.menuRepository,
                )
            }
        }
    }
}
