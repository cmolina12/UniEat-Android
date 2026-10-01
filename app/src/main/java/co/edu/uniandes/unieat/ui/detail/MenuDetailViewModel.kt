package co.edu.uniandes.unieat.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.decision.LocationGuidance
import co.edu.uniandes.unieat.core.decision.locationGuidance
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** Loads GET /menus/:id through [MenuRepository] and maps the outcome to [MenuDetailUiState]. */
class MenuDetailViewModel(
    private val menuId: String,
    private val repository: MenuRepository,
    private val deviceClock: () -> Instant = Instant::now,
) : ViewModel() {

    private val _state = MutableStateFlow<MenuDetailUiState>(MenuDetailUiState.Loading)
    val state: StateFlow<MenuDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = MenuDetailUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val response = repository.menu(menuId)
                MenuDetailUiState.Content(response.menu, Duration.between(deviceClock(), response.serverNow))
            } catch (e: ApiException) {
                when (e.code) {
                    ApiException.GONE -> MenuDetailUiState.Gone(e.error.message)
                    else -> MenuDetailUiState.Error(e.error.message, e.code)
                }
            }
        }
    }

    companion object {
        fun factory(menuId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                MenuDetailViewModel(menuId, app.container.menuRepository)
            }
        }
    }
}
