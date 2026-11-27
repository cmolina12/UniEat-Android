package co.edu.uniandes.unieat.ui.myreports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.ReportFilter
import co.edu.uniandes.unieat.core.model.ReportedPlace
import co.edu.uniandes.unieat.data.myreports.MyReportsRepository
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** Everything the "Mis reportes" list can show. The filter only hides rows, it never asks the server again. */
data class MyReportsUiState(
    val loading: Boolean = true,
    val places: List<ReportedPlace> = emptyList(),
    val filter: ReportFilter = ReportFilter.ALL,
    /** server clock of the last answer, for "hace 2 días" without trusting the phone */
    val serverNow: Instant? = null,
    val error: String? = null,
    val sessionExpired: Boolean = false,
) {
    val visible: List<ReportedPlace> get() = places.filter(filter::matches)
    val totalReports: Int get() = places.sumOf { it.total }
    val totalConfirmed: Int get() = places.sumOf { it.confirmed }

    fun count(option: ReportFilter): Int = places.count(option::matches)
}

/** Camilo, Sprint 4. Mis reportes: the places where the student reported and what happened to each report. */
class MyReportsViewModel(
    private val repository: MyReportsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MyReportsUiState())
    val state: StateFlow<MyReportsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val response = repository.places()
                _state.update {
                    it.copy(loading = false, places = response.places, serverNow = response.serverNow, error = null)
                }
            } catch (e: ApiException) {
                _state.update {
                    if (e.code == ApiException.AUTH_REQUIRED) it.copy(loading = false, sessionExpired = true)
                    else it.copy(loading = false, error = e.error.message)
                }
            }
        }
    }

    fun selectFilter(filter: ReportFilter) {
        _state.update { it.copy(filter = filter) }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                MyReportsViewModel(app.container.myReportsRepository)
            }
        }
    }
}
