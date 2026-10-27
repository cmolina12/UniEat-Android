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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the dashboard can show. One state at a time, so the UI is a simple `when`. */
sealed interface PerformanceUiState {
    data object Loading : PerformanceUiState

    data class Content(
        val summary: PerformanceSummary,
        val feedLoadReport: FeedLoadReport? = null,
        val ownEstablishmentsOnly: Boolean = false,
        val locationGuidance: LocationGuidanceSnapshot? = null,
        val locationCompleteness: LocationCompletenessSnapshot? = null,
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
) : ViewModel() {
    private val _state = MutableStateFlow<PerformanceUiState>(PerformanceUiState.Loading)
    val state: StateFlow<PerformanceUiState> = _state.asStateFlow()

    // 7 or 28 days, the two periods the backend supports
    private val _days = MutableStateFlow(7)
    val days: StateFlow<Int> = _days.asStateFlow()

    init {
        load()
    }

    /**
     * Fetches the metrics for the selected period. The outer coroutine runs on Main (viewModelScope);
     * inside it, withContext moves to IO and four child coroutines (async) ask the backend at the same
     * time. The screen waits for the slowest one instead of the sum of all four, and the result is
     * published back on Main.
     */
    fun load() {
        _state.value = PerformanceUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val restaurant = role == ROLE_RESTAURANT
                val days = _days.value
                withContext(io) {
                    val summary = async { if (restaurant) repository.restaurantPerformance(days) else repository.performance(days) }
                    val feedLoad = async { feedLoadReport() }
                    val guidance = async { locationGuidance(days, restaurant) }
                    val completeness = async { locationCompleteness(days) }
                    // Only the summary can fail the screen; the BQ cards are optional (null if they fail).
                    PerformanceUiState.Content(summary.await(), feedLoad.await(), restaurant, guidance.await(), completeness.await())
                }
            } catch (e: ApiException) {
                when (e.code) {
                    ApiException.FORBIDDEN -> PerformanceUiState.Restricted(e.error.message)
                    ApiException.AUTH_REQUIRED -> PerformanceUiState.SessionExpired
                    else -> PerformanceUiState.Error(e.error.message, e.code)
                }
            }
        }
    }

    /** BQ-01 always looks at the last seven days, whatever period the other metrics use. */
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
        load()
    }

    companion object {
        private const val BQ01_DAYS = 7
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
                )
            }
        }
    }
}
