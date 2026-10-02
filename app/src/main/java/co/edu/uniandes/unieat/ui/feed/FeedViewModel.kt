package co.edu.uniandes.unieat.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.data.analytics.EventKind
import co.edu.uniandes.unieat.data.analytics.EventTracker
import co.edu.uniandes.unieat.data.analytics.track
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** What the Feed screen can show. One state at a time, so the UI is a simple `when`. */
sealed interface FeedUiState {
    data object Loading : FeedUiState

    /**
     * Menus in backend rank-v1 order, already without expired or closed ones. Empty = no match.
     * [clockOffset] = server time − device time, so "vigente/por vencer" follows the server clock.
     */
    data class Content(val menus: List<DailyMenu>, val clockOffset: Duration) : FeedUiState

    /** AUTH_REQUIRED: the screen navigates back to the login. */
    data object SessionExpired : FeedUiState

    data class Error(val message: String, val code: String) : FeedUiState
}

/**
 * Loads GET /feed through [MenuRepository] and maps the outcome to [FeedUiState].
 * Observer pattern: the screen collects [state] and re-renders on every change.
 */
class FeedViewModel(
    private val repository: MenuRepository,
    private val eventTracker: EventTracker,
    private val deviceClock: () -> Instant = Instant::now,
) : ViewModel() {

    private val _state = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    // Backend defaults for now; the "Elige por mí" phase makes them editable.
    private val filters = FeedFilters()

    /** feed_impression once per menu per session, like the iOS `impressions` set. */
    private val impressed = mutableSetOf<String>()

    init {
        load()
    }

    /** Fetches the feed. The ranking comes from the backend; here we only hide inactive menus. */
    fun load() {
        _state.value = FeedUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val response = repository.feed(filters)
                FeedUiState.Content(
                    menus = response.menus.filter { it.closedAt == null && it.isActive(response.serverNow) },
                    clockOffset = Duration.between(deviceClock(), response.serverNow),
                )
            } catch (e: ApiException) {
                when (e.code) {
                    ApiException.AUTH_REQUIRED -> FeedUiState.SessionExpired
                    ApiException.OFFLINE -> FeedUiState.Error(
                        "Sin conexión. Revisa tu internet e inténtalo de nuevo.",
                        e.code,
                    )
                    else -> FeedUiState.Error(e.error.message, e.code)
                }
            }
        }
    }

    /** Tracked from the screen when a card actually becomes visible; retries never double-count. */
    fun onMenuShown(menu: DailyMenu) {
        if (impressed.add(menu.id)) eventTracker.track(EventKind.FEED_IMPRESSION, menu, SCREEN)
    }

    companion object {
        private const val SCREEN = "feed"

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                FeedViewModel(app.container.menuRepository, app.container.eventTracker)
            }
        }
    }
}
