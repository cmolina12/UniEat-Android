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
import co.edu.uniandes.unieat.core.model.ReportedPlaceResponse
import co.edu.uniandes.unieat.data.local.PendingReport
import co.edu.uniandes.unieat.data.myreports.MyReportsRepository
import co.edu.uniandes.unieat.data.myreports.ReportHistoryStore
import co.edu.uniandes.unieat.data.myreports.ReportedPlaceCache
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** set while the rows come from the phone (Room) instead of a fresh answer */
    val savedAt: Instant? = null,
    /** the last request failed for lack of signal; the screen explains it instead of showing an error */
    val offline: Boolean = false,
    /** reports made without signal, still in the Room queue */
    val unsent: List<PendingReport> = emptyList(),
) {
    val visible: List<ReportedPlace> get() = places.filter(filter::matches)
    val totalReports: Int get() = places.sumOf { it.total }
    val totalConfirmed: Int get() = places.sumOf { it.confirmed }

    fun count(option: ReportFilter): Int = places.count(option::matches)
}

/** Camilo, Sprint 4. Mis reportes: the places where the student reported and what happened to each report. */
class MyReportsViewModel(
    private val repository: MyReportsRepository,
    private val store: ReportHistoryStore? = null,
    online: Flow<Boolean>? = null,
    unsent: Flow<List<PendingReport>>? = null,
    private val cache: ReportedPlaceCache? = null,
    /** Tests pass their own dispatcher. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(MyReportsUiState())
    val state: StateFlow<MyReportsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** Background work after a load; cancelled by the next load so two never write at once. */
    private var prefetchJob: Job? = null

    init {
        load()
        // signal back: reload on its own, no need to tap Reintentar
        if (online != null) viewModelScope.launch {
            online.drop(1).filter { it }.collect { if (_state.value.offline || _state.value.error != null) load() }
        }
        // the queue went out (fewer unsent): those reports are on the server now, ask again
        if (unsent != null) viewModelScope.launch {
            unsent.collect { rows ->
                val sentSome = rows.size < _state.value.unsent.size
                _state.update { it.copy(unsent = rows) }
                if (sentSome) load()
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch { // Main: only here is the state changed
            // the phone's copy first, so the list is there while the request runs
            if (_state.value.places.isEmpty()) showSaved()
            try {
                val response = withContext(io) { repository.places() }
                // back on Main: the list shows now, it doesn't wait for the work below
                _state.update {
                    it.copy(
                        loading = false, places = response.places, serverNow = response.serverNow, error = null,
                        savedAt = null, offline = false,
                    )
                }
                afterLoad(response.places)
            } catch (e: ApiException) {
                _state.update {
                    when (e.code) {
                        ApiException.AUTH_REQUIRED -> it.copy(loading = false, sessionExpired = true)
                        ApiException.OFFLINE -> it.copy(loading = false, offline = true)
                        else -> it.copy(loading = false, error = e.error.message)
                    }
                }
            }
        }
    }

    /**
     * IO, in parallel: save the list in Room and fetch the first [PREFETCH] places at the same time, one
     * `async` each. Opening those places is then instant (LRU), and they also open without signal (Room).
     * A place that fails is skipped; the others go on.
     */
    private fun afterLoad(places: List<ReportedPlace>) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(io) {
            launch { store?.savePlaces(places) }
            val details = places.take(PREFETCH)
                .filter { cache?.get(it.establishmentId) == null }
                .map { place -> async { fetchQuietly(place.establishmentId) } }
                .awaitAll()
                .filterNotNull()
            details.forEach { detail ->
                cache?.put(detail)
                store?.savePlace(detail.place, detail.reports)
            }
        }
    }

    private suspend fun fetchQuietly(establishmentId: String): ReportedPlaceResponse? = try {
        repository.place(establishmentId)
    } catch (_: ApiException) {
        null
    }

    private suspend fun showSaved() {
        val saved = store?.loadPlaces() ?: return
        _state.update { it.copy(places = saved.places, savedAt = saved.savedAt) }
    }

    fun selectFilter(filter: ReportFilter) {
        _state.update { it.copy(filter = filter) }
    }

    companion object {
        /** About the rows that fit on a phone screen; more would be requests for places nobody looks at. */
        const val PREFETCH = 3

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                MyReportsViewModel(
                    app.container.myReportsRepository,
                    app.container.reportHistory,
                    app.container.connectivity.online,
                    app.container.reportQueue.unsent(),
                    app.container.reportedPlaceCache,
                )
            }
        }
    }
}
