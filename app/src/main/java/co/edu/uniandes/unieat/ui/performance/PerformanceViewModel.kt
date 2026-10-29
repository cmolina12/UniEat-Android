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
import co.edu.uniandes.unieat.data.remote.ApiException
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
        /** Set when this is the last result saved on the phone: offline, or while [refreshing]. */
        val savedAt: Instant? = null,
        /** The saved result is shown while the request is still on its way (slow signal). */
        val refreshing: Boolean = false,
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
    /** Where the requests run. Tests pass their own dispatcher so they control the timing. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** DataStore: the 7/28-day choice survives closing the app. */
    private val preferences: PerformancePreferences? = null,
    /** Last result per role and period, for offline. */
    private val snapshots: PerformanceSnapshots? = null,
    /** Validated-connection changes; coming back online replaces the saved copy with fresh numbers. */
    online: Flow<Boolean>? = null,
    private val clock: () -> Instant = Instant::now,
) : ViewModel() {
    private val _state = MutableStateFlow<PerformanceUiState>(PerformanceUiState.Loading)
    val state: StateFlow<PerformanceUiState> = _state.asStateFlow()

    // 7 or 28 days, the two periods the backend supports
    private val _days = MutableStateFlow(7)
    val days: StateFlow<Int> = _days.asStateFlow()

    /**
     * Last result of each period in memory, keyed by the number of days (7 or 28). Switching back to a
     * period shows its numbers at once while the fresh ones load (cache then network).
     *
     * SparseArrayCompat maps int keys to objects with two plain arrays: a sorted int[] of keys (found by
     * binary search) and an Object[] of values at the same index. Unlike HashMap<Int, …> it never boxes
     * the key into an Integer object and needs no entry objects, so it uses less memory; binary search is
     * O(log n) instead of O(1), which does not matter for 2 keys (it is meant for collections < 1000).
     * Initial capacity 2: exactly the two periods the backend supports, so it never has to grow.
     */
    private val byPeriod = SparseArrayCompat<PerformanceUiState.Content>(PERIODS)

    /** The request in flight. A new period cancels it, so a slow old answer can never overwrite the new one. */
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            // The saved period is read before the first request, so the dashboard opens as the user left it.
            preferences?.let { _days.value = it.days.first() }
            load()
        }
        if (online != null) viewModelScope.launch {
            online.collect { isOnline ->
                if (!isOnline) return@collect
                val current = _state.value
                val showingSaved = current is PerformanceUiState.Content && current.savedAt != null
                val offlineError = current is PerformanceUiState.Error && current.code == ApiException.OFFLINE
                if (showingSaved || offlineError) load()
            }
        }
    }

    /**
     * Fetches the metrics for the selected period. The outer coroutine runs on Main (viewModelScope);
     * inside it, withContext moves to IO and four child coroutines (async) ask the backend at the same
     * time. The screen waits for the slowest one instead of the sum of all four, and the result is
     * published back on Main.
     */
    fun load() {
        val days = _days.value
        _state.value = byPeriod[days] ?: PerformanceUiState.Loading
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Nothing in memory for this period: the result saved on disk appears while the request runs.
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
                    // Only the summary can fail the screen; the BQ cards are optional (null if they fail).
                    PerformanceUiState.Content(summary.await(), feedLoad.await(), restaurant, guidance.await(), completeness.await())
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

    /** Keeps this result for offline. A failed save is not worth an error on screen. */
    private suspend fun save(content: PerformanceUiState.Content, days: Int) {
        val store = snapshots ?: return
        runCatching {
            store.save(
                SavedPerformance(
                    role = roleKey, days = days, savedAt = clock(),
                    summary = content.summary, feedLoadReport = content.feedLoadReport,
                    ownEstablishmentsOnly = content.ownEstablishmentsOnly,
                    locationGuidance = content.locationGuidance, locationCompleteness = content.locationCompleteness,
                ),
            )
        }
    }

    private suspend fun savedCopy(days: Int): PerformanceUiState.Content? {
        val saved = snapshots?.load(roleKey, days) ?: return null
        return PerformanceUiState.Content(
            saved.summary, saved.feedLoadReport, saved.ownEstablishmentsOnly,
            saved.locationGuidance, saved.locationCompleteness, savedAt = saved.savedAt,
        )
    }

    private val roleKey: String get() = role ?: "none"

    /**
     * BQ-01 always looks at the last seven days, whatever period the other metrics use. Only admins ask
     * for it: the endpoint is admin-only, and a restaurant would get this phone's empty local summary
     * ("no loads") instead of a real answer.
     */
    private suspend fun feedLoadReport(): FeedLoadReport? =
        feedLoads?.let { runCatching { it.summary(BQ01_DAYS) }.getOrNull() }

    private suspend fun locationGuidance(days: Int, restaurant: Boolean): LocationGuidanceSnapshot? {
        if (!showLocationGuidance) return null
        return runCatching {
            if (restaurant) repository.restaurantLocationGuidance(days) else repository.locationGuidance(days)
        }.getOrNull()
    }

    /** BQ-10 (Camilo): shown with BQ-05, to the same roles; its failure never hides the rest. */
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
                )
            }
        }
    }
}
