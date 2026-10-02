package co.edu.uniandes.unieat.ui.feed

import co.edu.uniandes.unieat.core.decision.Coordinate
import co.edu.uniandes.unieat.core.model.CloseResponse
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import co.edu.uniandes.unieat.core.model.MenuBody
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import co.edu.uniandes.unieat.data.analytics.EventKind
import co.edu.uniandes.unieat.data.location.UserLocation
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.ui.detail.LocationPermission
import co.edu.uniandes.unieat.ui.detail.RecordingTracker
import co.edu.uniandes.unieat.ui.detail.StubLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val serverNow = Instant.parse("2026-10-01T17:00:00Z")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        tracker: RecordingTracker = RecordingTracker(),
        sharedFilters: MutableStateFlow<FeedFilters> = MutableStateFlow(FeedFilters()),
        location: StubLocation = StubLocation(),
        deviceClock: () -> Instant = { serverNow },
        feed: suspend (FeedFilters, String?) -> FeedResponse,
    ) = FeedViewModel(StubRepository(feed), tracker, sharedFilters, location, deviceClock)

    @Test
    fun startsLoadingThenShowsMenusInBackendOrder() = runTest(dispatcher) {
        val menus = listOf(menu("a"), menu("b"), menu("c"))
        // Device clock 90 s behind the server, like the detail test.
        val vm = viewModel(deviceClock = { serverNow - Duration.ofSeconds(90) }) { _, _ -> feedResponse(menus) }
        assertEquals(FeedUiState.Loading, vm.state.value)

        advanceUntilIdle()

        // rank-v1 order is the backend's; the ViewModel must not re-sort.
        assertEquals(FeedUiState.Content(menus, clockOffset = Duration.ofSeconds(90)), vm.state.value)
    }

    @Test
    fun expiredAndClosedMenusAreHidden() = runTest(dispatcher) {
        val active = menu("active")
        val expired = menu("expired").copy(validUntil = serverNow - Duration.ofMinutes(5))
        val closed = menu("closed").copy(closedAt = serverNow - Duration.ofMinutes(5))
        val vm = viewModel { _, _ -> feedResponse(listOf(expired, active, closed)) }

        advanceUntilIdle()

        assertEquals(listOf(active), (vm.state.value as FeedUiState.Content).menus)
    }

    @Test
    fun emptyFeedBecomesEmptyContent() = runTest(dispatcher) {
        val vm = viewModel { _, _ -> feedResponse(emptyList()) }
        advanceUntilIdle()
        assertEquals(emptyList<DailyMenu>(), (vm.state.value as FeedUiState.Content).menus)
    }

    @Test
    fun failureBecomesErrorAndRetryReloads() = runTest(dispatcher) {
        var calls = 0
        val vm = viewModel { _, _ ->
            calls++
            if (calls == 1) throw ApiException.unexpected(500) else feedResponse(listOf(menu("a")))
        }
        advanceUntilIdle()
        assertEquals(ApiException.INTERNAL_ERROR, (vm.state.value as FeedUiState.Error).code)

        vm.load()
        advanceUntilIdle()

        assertEquals(listOf(menu("a")), (vm.state.value as FeedUiState.Content).menus)
    }

    @Test
    fun offlineBecomesAClearError() = runTest(dispatcher) {
        val vm = viewModel { _, _ -> throw ApiException.offline() }
        advanceUntilIdle()
        assertEquals(
            FeedUiState.Error("Sin conexión. Revisa tu internet e inténtalo de nuevo.", ApiException.OFFLINE),
            vm.state.value,
        )
    }

    @Test
    fun authRequiredBecomesSessionExpired() = runTest(dispatcher) {
        val vm = viewModel { _, _ -> throw ApiException.authRequired() }
        advanceUntilIdle()
        assertEquals(FeedUiState.SessionExpired, vm.state.value)
    }

    @Test
    fun initialLoadUsesTheSharedFilters() = runTest(dispatcher) {
        val seen = mutableListOf<FeedFilters>()
        val filters = MutableStateFlow(FeedFilters(budgetCop = 15_000, diet = "vegetarian"))
        viewModel(sharedFilters = filters) { f, _ ->
            seen += f
            feedResponse(emptyList())
        }
        advanceUntilIdle()

        assertEquals(listOf(FeedFilters(budgetCop = 15_000, diet = "vegetarian")), seen)
    }

    @Test
    fun applyFiltersReloadsWithTheNewOnes() = runTest(dispatcher) {
        val seen = mutableListOf<FeedFilters>()
        val vm = viewModel { f, _ ->
            seen += f
            feedResponse(listOf(menu("a")))
        }
        advanceUntilIdle()

        vm.applyFilters(FeedFilters(budgetCop = 10_000, area = "Norte"))
        advanceUntilIdle()

        assertEquals(FeedFilters(budgetCop = 10_000, area = "Norte"), seen.last())
        assertEquals(FeedFilters(budgetCop = 10_000, area = "Norte"), vm.filters.value)
        assertEquals(2, seen.size)
    }

    @Test
    fun applyingFiltersInOneTabReloadsTheOtherTab() = runTest(dispatcher) {
        // Both tabs ("Hoy" and "Elige por mí") observe the same shared filters flow.
        val shared = MutableStateFlow(FeedFilters())
        val seenByOther = mutableListOf<FeedFilters>()
        val feedTab = viewModel(sharedFilters = shared) { _, _ -> feedResponse(listOf(menu("a"))) }
        viewModel(sharedFilters = shared) { f, _ ->
            seenByOther += f
            feedResponse(listOf(menu("a")))
        }
        advanceUntilIdle()

        feedTab.applyFilters(FeedFilters(diet = "vegan"))
        advanceUntilIdle()

        assertEquals(FeedFilters(diet = "vegan"), seenByOther.last())
    }

    @Test
    fun recommendationFollowsTheBackendOrderAndWrapsAround() = runTest(dispatcher) {
        val content = FeedUiState.Content(listOf(menu("a"), menu("b")), Duration.ZERO)

        assertEquals(menu("a"), content.recommendation(0)) // backend's best option
        assertEquals(menu("b"), content.recommendation(1))
        assertEquals(menu("a"), content.recommendation(2)) // wraps around

        assertNull(FeedUiState.Content(emptyList(), Duration.ZERO).recommendation(0))
    }

    @Test
    fun nextRecommendationAdvancesAndANewLoadResetsIt() = runTest(dispatcher) {
        val vm = viewModel { _, _ -> feedResponse(listOf(menu("a"), menu("b"))) }
        advanceUntilIdle()

        vm.nextRecommendation()
        assertEquals(1, vm.recommendationIndex.value)

        vm.applyFilters(FeedFilters(area = "Sur")) // new load: back to the best option
        advanceUntilIdle()

        assertEquals(0, vm.recommendationIndex.value)
    }

    @Test
    fun originIsSentWhenPermissionGrantedAndFixAvailable() = runTest(dispatcher) {
        val seenOrigins = mutableListOf<String?>()
        val location = StubLocation()
        location.fixes.emit(UserLocation(Coordinate(4.6028, -74.0652), accuracyMeters = 10f))
        val vm = viewModel(location = location) { _, origin ->
            seenOrigins += origin
            feedResponse(listOf(menu("a")))
        }
        vm.onLocationPermissionChecked(LocationPermission.PRECISE)
        advanceUntilIdle()

        assertEquals("4.6028,-74.0652", seenOrigins.last())
        assertEquals(true, location.lastPrecise)
    }

    @Test
    fun withoutPermissionTheFeedLoadsWithoutOrigin() = runTest(dispatcher) {
        val seenOrigins = mutableListOf<String?>()
        val location = StubLocation()
        viewModel(location = location) { _, origin ->
            seenOrigins += origin
            feedResponse(listOf(menu("a")))
        }
        advanceUntilIdle()

        assertEquals(listOf(null as String?), seenOrigins)
        assertEquals(0, location.collectors) // the GPS is never even started
    }

    @Test
    fun locationTurnedOffLoadsWithoutOrigin() = runTest(dispatcher) {
        val seenOrigins = mutableListOf<String?>()
        val location = StubLocation(enabled = false)
        val vm = viewModel(location = location) { _, origin ->
            seenOrigins += origin
            feedResponse(listOf(menu("a")))
        }
        vm.onLocationPermissionChecked(LocationPermission.PRECISE)
        advanceUntilIdle()

        assertNull(seenOrigins.last())
        assertEquals(0, location.collectors)
    }

    @Test
    fun fixNotArrivingInTimeStillLoadsTheFeed() = runTest(dispatcher) {
        val seenOrigins = mutableListOf<String?>()
        val location = StubLocation() // enabled, but never emits a fix: the 2 s timeout wins
        val vm = viewModel(location = location) { _, origin ->
            seenOrigins += origin
            feedResponse(listOf(menu("a")))
        }
        vm.onLocationPermissionChecked(LocationPermission.PRECISE)
        advanceUntilIdle()

        assertNull(seenOrigins.last())
        assertEquals(listOf(menu("a")), (vm.state.value as FeedUiState.Content).menus)
    }

    @Test
    fun recheckingTheSamePermissionDoesNotReload() = runTest(dispatcher) {
        var calls = 0
        val vm = viewModel { _, _ ->
            calls++
            feedResponse(listOf(menu("a")))
        }
        advanceUntilIdle()

        vm.onLocationPermissionChecked(LocationPermission.NONE) // every resume reports again
        advanceUntilIdle()

        assertEquals(1, calls)
    }

    @Test
    fun impressionIsTrackedOncePerMenu() = runTest(dispatcher) {
        val tracker = RecordingTracker()
        val a = menu("a").copy(version = 2)
        val b = menu("b")
        val vm = viewModel(tracker = tracker) { _, _ -> feedResponse(listOf(a, b)) }
        advanceUntilIdle()

        vm.onMenuShown(a)
        vm.onMenuShown(a) // scrolled back into view: must not count again
        vm.onMenuShown(b)

        assertEquals(
            listOf(
                RecordingTracker.Tracked(EventKind.FEED_IMPRESSION, "a", 2, "feed", null),
                RecordingTracker.Tracked(EventKind.FEED_IMPRESSION, "b", 1, "feed", null),
            ),
            tracker.events,
        )
    }

    private fun feedResponse(menus: List<DailyMenu>) =
        FeedResponse(serverNow = serverNow, fetchedAt = serverNow, menus = menus, resultCount = menus.size)

    private fun menu(id: String) = DailyMenu(
        id = id, title = "Menú $id", validUntil = serverNow + Duration.ofHours(1),
        publishedAt = serverNow - Duration.ofHours(4), establishmentId = "e",
        establishmentName = "Local $id", area = "Centro", lowestPriceCop = 12_000,
    )

    internal class StubRepository(private val onFeed: suspend (FeedFilters, String?) -> FeedResponse) : MenuRepository {
        override suspend fun feed(filters: FeedFilters, origin: String?): FeedResponse = onFeed(filters, origin)
        override suspend fun menu(id: String): MenuDetailResponse = error("unused")
        override suspend fun myMenus(): List<DailyMenu> = error("unused")
        override suspend fun publish(body: MenuBody): DailyMenu = error("unused")
        override suspend fun revise(id: String, body: MenuBody): DailyMenu = error("unused")
        override suspend fun close(id: String, expectedVersion: Int?): CloseResponse = error("unused")
    }
}
