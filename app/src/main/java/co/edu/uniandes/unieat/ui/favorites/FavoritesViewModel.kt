package co.edu.uniandes.unieat.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.data.local.Favorite
import co.edu.uniandes.unieat.data.local.Favorites
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FavoriteRow(val favorite: Favorite, val menu: DailyMenu?)

sealed interface FavoritesUiState {
    data object Loading : FavoritesUiState

    data class Content(val rows: List<FavoriteRow>, val error: String? = null) : FavoritesUiState
}

class FavoritesViewModel(
    private val favorites: Favorites,
    private val menus: MenuRepository,
    online: Flow<Boolean>,
) : ViewModel() {
    private val _state = MutableStateFlow<FavoritesUiState>(FavoritesUiState.Loading)
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    private var lastLoadedAt = 0L

    init {
        viewModelScope.launch {
            online.collect { isOnline ->
                val current = _state.value
                if (isOnline) {
                    if (current is FavoritesUiState.Content) {
                        if (current.error != null) {
                            load()
                        }
                    }
                }
            }
        }
    }

    fun load() {
        val current = _state.value
        if (current is FavoritesUiState.Content) {
            if (current.error == null) {
                if (System.currentTimeMillis() - lastLoadedAt < 60_000) {
                    return
                }
            }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val today = mutableListOf<DailyMenu>()
            var error: String? = null
            try {
                val feed = menus.feedForDashboard()
                for (menu in feed.menus) {
                    if (menu.isActive(feed.serverNow)) today.add(menu)
                }
            } catch (e: ApiException) {
                if (e.code == ApiException.OFFLINE) {
                    error = "Sin conexión. Tus favoritos están guardados en el teléfono. Los menús de hoy aparecen cuando vuelva la señal."
                } else {
                    error = "No pudimos cargar los menús de hoy. " + e.error.message
                }
            }

            val rows = mutableListOf<FavoriteRow>()
            for (favorite in favorites.all()) {
                var found: DailyMenu? = null
                for (menu in today) {
                    if (menu.establishmentId == favorite.id) {
                        found = menu
                        break
                    }
                }
                rows.add(FavoriteRow(favorite, found))
            }
            _state.value = FavoritesUiState.Content(rows, error)
            lastLoadedAt = System.currentTimeMillis()
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                FavoritesViewModel(app.container.favorites, app.container.menuRepository, app.container.connectivity.online)
            }
        }
    }
}
