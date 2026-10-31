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
import co.edu.uniandes.unieat.data.pilot.ContentSource
import co.edu.uniandes.unieat.data.pilot.FeedPilot
import co.edu.uniandes.unieat.data.pilot.PilotVariant
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.CachedFeed
import co.edu.uniandes.unieat.data.repository.FeedCache
import co.edu.uniandes.unieat.data.repository.FeedCachePolicy
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.data.repository.savedClockOffset
import co.edu.uniandes.unieat.data.telemetry.FeedLoadTelemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What the Feed screen can show. One state at a time, so the UI is a simple `when`. */
sealed interface FeedUiState {
    data object Loading : FeedUiState

    /**
     * Menus in backend rank-v1 order, already without expired or closed ones. Empty = no match.
     * [clockOffset] = server time − device time, so "vigente/por vencer" follows the server clock.
     * [savedAt]: menus from the saved copy (offline, or while [refreshing]).
     * [refreshing]: saved copy shown while the request is still running (slow signal).
     */
    data class Content(
        val menus: List<DailyMenu>,
        val clockOffset: Duration,
        val loadId: String? = null,
        val savedAt: Instant? = null,
        val refreshing: Boolean = false,
    ) : FeedUiState

    /** AUTH_REQUIRED: the screen navigates back to the login. */
    data object SessionExpired : FeedUiState

    data class Error(val message: String, val code: String) : FeedUiState
}

/**
 * Loads GET /feed through [MenuRepository] and maps the outcome to [FeedUiState].
 * Observer pattern: the screen collects [state] and re-renders on every change.
 * The filters flow is shared with the other tab, so applying filters reloads both.
 */
class FeedViewModel(
    private val repository: MenuRepository,
    private val eventTracker: EventTracker,
    private val sharedFilters: MutableStateFlow<FeedFilters>,
    private val deviceClock: () -> Instant = Instant::now,
    /** BQ-01 load telemetry (Samuel). Optional so the existing tests keep their constructor. */
    private val telemetry: FeedLoadTelemetry? = null,
    private val cache: FeedCache? = null,
    /** Offline: the menus on screen become the saved copy with its time. Back online: reload. */
    online: Flow<Boolean>? = null,
    /** BQ-13 cache pilot (Samuel): decides the [FeedCachePolicy] and receives what happened. null = no pilot. */
    private val pilot: FeedPilot? = null,
    /**
     * Only the "Hoy" tab reports to the pilot. "Elige por mí" uses the same ViewModel class and follows the
     * same policy, but its loads must not open pilot visits: BQ-13 measures the first feed the student sees.
     */
    private val recordsPilot: Boolean = true,
) : ViewModel() {

    private val _state = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    /** Current filters, for the sheet and the summary. */
    val filters: StateFlow<FeedFilters> = sharedFilters.asStateFlow()

    private val _recommendationIndex = MutableStateFlow(0)
    val recommendationIndex: StateFlow<Int> = _recommendationIndex.asStateFlow()

    // Strategy pattern: the active criterion for "Elige por mí".
    private val _strategy = MutableStateFlow<RecommendationStrategy>(BestRankedStrategy)
    val strategy: StateFlow<RecommendationStrategy> = _strategy.asStateFlow()

    /** feed_impression once per menu per session, like the iOS `impressions` set. */
    private val impressed = mutableSetOf<String>()

    /** BQ-13: token of the pilot load in flight; only touched on Main (fetch and onContentDrawn). */
    private var pilotToken: String? = null

    init {
        // Every filter change reloads; collectLatest drops a request whose filters are stale.
        viewModelScope.launch {
            sharedFilters.collectLatest { fetch(it) }
        }
        if (online != null) viewModelScope.launch {
            online.collect { isOnline ->
                if (isOnline) { if (isShowingOfflineState()) load() } else markAsSavedCopy()
            }
        }
    }

    /** The live menus were saved when they arrived, that copy has the time for the notice. */
    private suspend fun markAsSavedCopy() {
        val live = _state.value as? FeedUiState.Content ?: return
        if (live.savedAt != null) return
        // same rules as a load: no copy past 24 h, and none for the pilot's online-only group
        val saved = cache?.load(sharedFilters.value)
            ?.takeIf { FeedCachePolicy.isEligible(it, deviceClock()) }
            ?.let { FeedCachePolicy.of(pilot?.variant()).copyWhenOffline(it) }
            ?: return
        // a new load may have started meanwhile, then it owns the state
        if (_state.value === live) _state.value = savedCopy(saved)
    }

    private fun isShowingOfflineState() = when (val current = _state.value) {
        is FeedUiState.Error -> current.code == ApiException.OFFLINE
        is FeedUiState.Content -> current.savedAt != null
        else -> false
    }

    /** Retry with the current filters. */
    fun load() {
        val current = _state.value
        if (current is FeedUiState.Loading || (current is FeedUiState.Content && current.refreshing)) return
        viewModelScope.launch { fetch(sharedFilters.value) }
    }

    /** From the filters sheet. The shared flow emits and both tabs reload. */
    fun applyFilters(filters: FeedFilters) {
        sharedFilters.value = filters
    }

    /** "Elegir otra opción": move to the next menu of the active criterion. */
    fun nextRecommendation() = _recommendationIndex.update { it + 1 }

    /** Switch the recommendation criterion and start again at its best option. */
    fun selectStrategy(strategy: RecommendationStrategy) {
        _strategy.value = strategy
        _recommendationIndex.value = 0
    }

    /** Tracked from the screen when a card actually becomes visible; retries never double-count. */
    fun onMenuShown(menu: DailyMenu) {
        if (impressed.add(menu.id)) eventTracker.track(EventKind.FEED_IMPRESSION, menu, SCREEN)
    }

    fun onFeedRendered(loadId: String?) {
        if (loadId == null || telemetry == null) return
        viewModelScope.launch { telemetry.rendered(loadId) }
    }

    /**
     * BQ-13 (Samuel): called by FeedScreen once menus are actually on screen, for the saved copy and for
     * the fresh network result. The clock is read here, on Main, at the moment of the draw; the write to
     * Room happens later in a coroutine and does not delay the screen.
     */
    fun onContentDrawn(content: FeedUiState.Content) {
        val token = pilotToken ?: return
        val probe = pilot ?: return
        val at = deviceClock().toEpochMilli()
        val source = if (content.savedAt != null) ContentSource.CACHE else ContentSource.NETWORK
        viewModelScope.launch { probe.onContentDrawn(token, source, at) }
    }

    /** Fetches the feed. The ranking comes from the backend; here we only hide inactive menus. */
    private suspend fun fetch(filters: FeedFilters) {
        val attempt = telemetry?.start()
        // BQ-13 (Samuel): the pilot group picks how the saved copy is used (Strategy). Outside the pilot
        // the variant is null and the policy is CacheThenNetwork, i.e. the app behaves exactly as before.
        val variant = pilot?.variant()
        val policy = FeedCachePolicy.of(variant)
        val startedAt = deviceClock()
        // TTL: only a copy saved in the last 24 h is eligible; an older one can only hold expired menus.
        val saved = cache?.load(filters)?.takeIf { FeedCachePolicy.isEligible(it, startedAt) }
        val token = startPilotLoad(variant, startedAt, saved)
        // Cache then network: with a saved copy for these filters the student sees menus at once, even on a
        // weak signal; the request replaces them, or the copy stays with its offline notice if it fails.
        _state.value = policy.copyWhileLoading(saved)?.let { savedCopy(it).copy(refreshing = true) } ?: FeedUiState.Loading
        _recommendationIndex.value = 0 // a new load starts again at the backend's best option
        val next = try {
            val response = repository.feed(filters)
            if (attempt != null) telemetry?.success(attempt)
            // Both groups keep saving: the copy is what makes "was a copy available?" measurable in both,
            // and a student who leaves the online-only group gets a working cache at once.
            cache?.save(CachedFeed(filters, response, savedAt = deviceClock()))
            FeedUiState.Content(
                menus = response.menus.filter { it.closedAt == null && it.isActive(response.serverNow) },
                clockOffset = Duration.between(deviceClock(), response.serverNow),
                loadId = attempt?.loadId,
            )
        } catch (e: ApiException) {
            if (attempt != null) telemetry?.failure(attempt, e.code)
            when (e.code) {
                ApiException.AUTH_REQUIRED -> FeedUiState.SessionExpired
                ApiException.OFFLINE -> policy.copyWhenOffline(saved)?.let(::savedCopy) ?: FeedUiState.Error(
                    if (variant == PilotVariant.ONLINE_ONLY) PILOT_OFFLINE_MESSAGE else OFFLINE_MESSAGE,
                    e.code,
                )
                else -> FeedUiState.Error(e.error.message, e.code)
            }
        }
        _state.value = next
        // A fresh network result is reported when it is drawn; here only the request that failed.
        if (token != null && (next !is FeedUiState.Content || next.savedAt != null)) {
            val showedCopy = next is FeedUiState.Content
            pilot?.let { probe -> viewModelScope.launch { probe.onLoadFailed(token, showedCopy) } }
        }
    }

    /**
     * Opens a pilot load when the user is in the pilot and this is the "Hoy" tab. The call to the pilot is
     * launched, not awaited: writing to Room must not delay the saved copy on screen, which is precisely
     * the time BQ-13 measures. The pilot's Mutex keeps the order of start → draw → failure.
     */
    private fun startPilotLoad(variant: PilotVariant?, startedAt: Instant, saved: CachedFeed?): String? {
        pilotToken = null
        val probe = pilot ?: return null
        if (variant == null || !recordsPilot) return null
        val token = UUID.randomUUID().toString()
        val cacheAgeMs = saved?.let { FeedCachePolicy.ageOf(it, startedAt).toMillis() }
        pilotToken = token
        viewModelScope.launch { probe.onLoadStarted(token, variant, startedAt.toEpochMilli(), cacheAgeMs) }
        return token
    }

    /** Keeps the clock offset from when it was saved, so validity still follows the server. */
    private fun savedCopy(saved: CachedFeed): FeedUiState.Content {
        val deviceNow = deviceClock()
        val offset = savedClockOffset(saved.savedAt, saved.response.serverNow, deviceNow)
        val serverNow = deviceNow + offset
        return FeedUiState.Content(
            menus = saved.response.menus.filter { it.closedAt == null && it.isActive(serverNow) },
            clockOffset = offset,
            savedAt = saved.savedAt,
        )
    }

    companion object {
        private const val SCREEN = "feed"
        internal const val OFFLINE_MESSAGE = "Sin conexión. Revisa tu internet e inténtalo de nuevo."
        /** Online-only group: the honest reason there is no saved copy, and the way out. */
        internal const val PILOT_OFFLINE_MESSAGE =
            "Sin conexión. Estás en el grupo «solo en línea» del piloto de caché, así que no usamos menús guardados. " +
                "Puedes salir del piloto en Perfil y vuelven de inmediato."

        /** [recordsPilot] false for "Elige por mí": same pilot policy, but it does not open pilot visits. */
        fun factory(recordsPilot: Boolean = true): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                FeedViewModel(
                    app.container.menuRepository,
                    app.container.eventTracker,
                    app.container.feedFilters,
                    telemetry = app.container.feedLoadTelemetry,
                    cache = app.container.feedCache,
                    online = app.container.connectivity.online,
                    pilot = app.container.cachePilot,
                    recordsPilot = recordsPilot,
                )
            }
        }
    }
}
