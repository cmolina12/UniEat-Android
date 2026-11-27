package co.edu.uniandes.unieat.ui.saved

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.data.analytics.EventKind
import co.edu.uniandes.unieat.data.analytics.EventTracker
import co.edu.uniandes.unieat.data.analytics.track
import co.edu.uniandes.unieat.data.saved.BookmarkedMenu
import co.edu.uniandes.unieat.data.saved.SavedMenus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the "Guardados" list shows. [offline] only changes the notice: the list itself is always local. */
data class SavedMenusUiState(
    val loading: Boolean = true,
    val menus: List<BookmarkedMenu> = emptyList(),
    val offline: Boolean = false,
)

/**
 * Samuel — Sprint 4, "Guardados". The list and the save button of the detail share this ViewModel.
 * The screen only renders [state]; the saved rows come from Room as a `Flow`, so a change made from the
 * detail button shows up in the list without any refresh code. Connectivity only decides whether the
 * "Sin conexión" notice appears.
 */
class SavedMenusViewModel(
    private val saved: SavedMenus,
    online: Flow<Boolean>,
    private val events: EventTracker? = null,
) : ViewModel() {
    val state: StateFlow<SavedMenusUiState> = combine(saved.all(), online) { menus, isOnline ->
        SavedMenusUiState(loading = false, menus = menus, offline = !isOnline)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SavedMenusUiState())

    fun isSaved(menuId: String): Flow<Boolean> = saved.isSaved(menuId)

    fun toggle(menu: DailyMenu) {
        viewModelScope.launch {
            // Same event as the iOS saved menus: only when the menu is added, not when it is removed.
            if (saved.toggle(menu)) events?.track(EventKind.MENU_SAVE, menu, SCREEN)
        }
    }

    fun remove(menuId: String) {
        viewModelScope.launch { saved.remove(menuId) }
    }

    /** Called when the list opens: bookmarks whose menu ended long ago have nothing behind them. */
    fun purgeOld() {
        viewModelScope.launch { saved.purgeOld() }
    }

    companion object {
        private const val SCREEN = "detail"

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                SavedMenusViewModel(app.container.savedMenus, app.container.connectivity.online, app.container.eventTracker)
            }
        }
    }
}
