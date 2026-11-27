package co.edu.uniandes.unieat.ui.myreports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.MyReport
import co.edu.uniandes.unieat.core.model.ReportedPlace
import co.edu.uniandes.unieat.data.myreports.MyReportsRepository
import co.edu.uniandes.unieat.data.myreports.ReportHistoryStore
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class ReportedPlaceUiState(
    val loading: Boolean = true,
    /** null until the first answer; the header uses the name and area from the list meanwhile */
    val place: ReportedPlace? = null,
    val reports: List<MyReport> = emptyList(),
    val error: String? = null,
    val sessionExpired: Boolean = false,
    /** set while the reports come from the phone (Room) */
    val savedAt: Instant? = null,
)

/** One place of Mis reportes: every report the student sent there, newest first. */
class ReportedPlaceViewModel(
    private val establishmentId: String,
    private val repository: MyReportsRepository,
    private val store: ReportHistoryStore? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(ReportedPlaceUiState())
    val state: StateFlow<ReportedPlaceUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            if (_state.value.reports.isEmpty()) {
                store?.loadPlace(establishmentId)?.let { saved ->
                    _state.update { it.copy(place = saved.place, reports = saved.reports, savedAt = saved.savedAt) }
                }
            }
            try {
                val response = repository.place(establishmentId)
                _state.update {
                    it.copy(loading = false, place = response.place, reports = response.reports, error = null, savedAt = null)
                }
                store?.savePlace(response.place, response.reports)
            } catch (e: ApiException) {
                _state.update {
                    if (e.code == ApiException.AUTH_REQUIRED) it.copy(loading = false, sessionExpired = true)
                    else it.copy(loading = false, error = e.error.message)
                }
            }
        }
    }

    companion object {
        fun factory(establishmentId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                ReportedPlaceViewModel(establishmentId, app.container.myReportsRepository, app.container.reportHistory)
            }
        }
    }
}
