package co.edu.uniandes.unieat.ui.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.telemetry.FeedLoadReport
import co.edu.uniandes.unieat.data.telemetry.FeedLoadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the dashboard can show. One state at a time, so the UI is a simple `when`. */
sealed interface PerformanceUiState {
    data object Loading : PerformanceUiState

    /** Metrics computed by the backend for the selected period. */
    data class Content(val summary: PerformanceSummary, val feedLoadReport: FeedLoadReport? = null) : PerformanceUiState

    /** FORBIDDEN: metrics belong to restaurant or admin accounts. */
    data class Restricted(val message: String) : PerformanceUiState

    /** AUTH_REQUIRED: the screen navigates back to the login. */
    data object SessionExpired : PerformanceUiState

    data class Error(val message: String, val code: String) : PerformanceUiState
}

/**
 * Loads GET /performance through [AnalyticsRepository] and maps the outcome to [PerformanceUiState].
 * The numbers come from the backend's event pipeline; nothing is computed on the client.
 */
class PerformanceViewModel(
    private val repository: AnalyticsRepository,
    /** BQ-01 (Samuel): its card is extra; if it fails, the rest of the dashboard still loads. */
    private val feedLoads: FeedLoadRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<PerformanceUiState>(PerformanceUiState.Loading)
    val state: StateFlow<PerformanceUiState> = _state.asStateFlow()

    // 7 or 28 days, the two periods the backend supports
    private val _days = MutableStateFlow(7)
    val days: StateFlow<Int> = _days.asStateFlow()

    init {
        load()
    }

    /** Fetches the metrics for the selected period. */
    fun load() {
        _state.value = PerformanceUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                PerformanceUiState.Content(repository.performance(_days.value), feedLoadReport())
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

    /** Switch between 7 and 28 days; picking the same period again does nothing. */
    fun selectDays(days: Int) {
        if (_days.value == days) return
        _days.value = days
        load()
    }

    companion object {
        private const val BQ01_DAYS = 7

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                PerformanceViewModel(app.container.analyticsRepository, app.container.feedLoadRepository)
            }
        }
    }
}
