package co.edu.uniandes.unieat.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.data.local.FavoriteRestaurant
import co.edu.uniandes.unieat.data.local.FavoriteRestaurants
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FavoriteRow(val favorite: FavoriteRestaurant, val todayMenu: DailyMenu?)

sealed interface FavoritesUiState {
    data object Loading : FavoritesUiState

    data class Content(val rows: List<FavoriteRow>, val menusError: String? = null) : FavoritesUiState
}

class FavoritesViewModel(
    private val favorites: FavoriteRestaurants,
    private val menus: MenuRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<FavoritesUiState>(FavoritesUiState.Loading)
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val saved = favorites.all()
            _state.value = try {
                val feed = menus.feedForDashboard()
                val today = feed.menus.filter { it.isActive(feed.serverNow) }
                FavoritesUiState.Content(saved.map { f -> FavoriteRow(f, today.firstOrNull { it.establishmentId == f.establishmentId }) })
            } catch (e: ApiException) {
                FavoritesUiState.Content(saved.map { FavoriteRow(it, null) }, menusError = e.error.message)
            }
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                FavoritesViewModel(app.container.favorites, app.container.menuRepository)
            }
        }
    }
}
