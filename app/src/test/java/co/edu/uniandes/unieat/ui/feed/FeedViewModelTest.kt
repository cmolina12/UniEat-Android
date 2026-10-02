package co.edu.uniandes.unieat.ui.feed

import co.edu.uniandes.unieat.core.model.CloseResponse
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import co.edu.uniandes.unieat.core.model.MenuBody
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import co.edu.uniandes.unieat.data.analytics.EventKind
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
import co.edu.uniandes.unieat.ui.detail.RecordingTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        deviceClock: () -> Instant = { serverNow },
        feed: suspend () -> FeedResponse,
    ) = FeedViewModel(StubRepository(feed), tracker, deviceClock)

    @Test
    fun startsLoadingThenShowsMenusInBackendOrder() = runTest(dispatcher) {
        val menus = listOf(menu("a"), menu("b"), menu("c"))
        // Device clock 90 s behind the server, like the detail test.
        val vm = viewModel(deviceClock = { serverNow - Duration.ofSeconds(90) }) { feedResponse(menus) }
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
        val vm = viewModel { feedResponse(listOf(expired, active, closed)) }

        advanceUntilIdle()

        assertEquals(listOf(active), (vm.state.value as FeedUiState.Content).menus)
    }

    @Test
    fun emptyFeedBecomesEmptyContent() = runTest(dispatcher) {
        val vm = viewModel { feedResponse(emptyList()) }
        advanceUntilIdle()
        assertEquals(emptyList<DailyMenu>(), (vm.state.value as FeedUiState.Content).menus)
    }

    @Test
    fun failureBecomesErrorAndRetryReloads() = runTest(dispatcher) {
        var calls = 0
        val vm = viewModel {
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
        val vm = viewModel { throw ApiException.offline() }
        advanceUntilIdle()
        assertEquals(
            FeedUiState.Error("Sin conexión. Revisa tu internet e inténtalo de nuevo.", ApiException.OFFLINE),
            vm.state.value,
        )
    }

    @Test
    fun authRequiredBecomesSessionExpired() = runTest(dispatcher) {
        val vm = viewModel { throw ApiException.authRequired() }
        advanceUntilIdle()
        assertEquals(FeedUiState.SessionExpired, vm.state.value)
    }

    @Test
    fun impressionIsTrackedOncePerMenu() = runTest(dispatcher) {
        val tracker = RecordingTracker()
        val a = menu("a").copy(version = 2)
        val b = menu("b")
        val vm = viewModel(tracker = tracker) { feedResponse(listOf(a, b)) }
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

    internal class StubRepository(private val feed: suspend () -> FeedResponse) : MenuRepository {
        override suspend fun feed(filters: FeedFilters): FeedResponse = feed()
        override suspend fun menu(id: String): MenuDetailResponse = error("unused")
        override suspend fun myMenus(): List<DailyMenu> = error("unused")
        override suspend fun publish(body: MenuBody): DailyMenu = error("unused")
        override suspend fun revise(id: String, body: MenuBody): DailyMenu = error("unused")
        override suspend fun close(id: String, expectedVersion: Int?): CloseResponse = error("unused")
    }
}
