package co.edu.uniandes.unieat.ui.detail

import co.edu.uniandes.unieat.core.model.CloseResponse
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import co.edu.uniandes.unieat.core.model.MenuBody
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import co.edu.uniandes.unieat.data.analytics.EventKind
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.MenuRepository
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
class MenuDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val serverNow = Instant.parse("2026-09-29T17:00:00Z")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun startsLoadingThenShowsContentWithClockOffset() = runTest(dispatcher) {
        val menu = menu()
        val vm = MenuDetailViewModel("m", StubRepository { MenuDetailResponse(serverNow, serverNow, menu) }, StubLocation(), RecordingTracker(), StubReports()) {
            serverNow - Duration.ofSeconds(90) // device clock 90 s behind the server
        }
        assertEquals(MenuDetailUiState.Loading, vm.state.value)

        advanceUntilIdle()

        assertEquals(MenuDetailUiState.Content(menu, Duration.ofSeconds(90)), vm.state.value)
    }

    @Test
    fun goneBecomesGoneState() = runTest(dispatcher) {
        val vm = MenuDetailViewModel("m", StubRepository { throw apiError(ApiException.GONE, "Este menú ya venció", 410) }, StubLocation(), RecordingTracker(), StubReports())
        advanceUntilIdle()
        assertEquals(MenuDetailUiState.Gone("Este menú ya venció"), vm.state.value)
    }

    @Test
    fun otherFailuresBecomeErrorAndRetryReloads() = runTest(dispatcher) {
        var calls = 0
        val vm = MenuDetailViewModel("m", StubRepository {
            calls++
            if (calls == 1) throw ApiException.offline() else MenuDetailResponse(serverNow, serverNow, menu())
        }, StubLocation(), RecordingTracker(), StubReports()) { serverNow }
        advanceUntilIdle()
        assertEquals(ApiException.OFFLINE, (vm.state.value as MenuDetailUiState.Error).code)

        vm.load()
        advanceUntilIdle()

        assertEquals(MenuDetailUiState.Content(menu(), Duration.ZERO), vm.state.value)
    }

    @Test
    fun detailOpenIsTrackedOnceWithTheVersionShown() = runTest(dispatcher) {
        val tracker = RecordingTracker()
        val menu = menu().copy(version = 3)
        val vm = MenuDetailViewModel("m", StubRepository { MenuDetailResponse(serverNow, serverNow, menu) }, StubLocation(), tracker, StubReports()) { serverNow }
        advanceUntilIdle()
        vm.load() // retry / pull-to-refresh must not count a second opening
        advanceUntilIdle()

        assertEquals(
            listOf(RecordingTracker.Tracked(EventKind.DETAIL_OPEN, "m", 3, "detail", null)),
            tracker.events,
        )
    }

    @Test
    fun goneMenuIsNotTrackedAsOpened() = runTest(dispatcher) {
        val tracker = RecordingTracker()
        MenuDetailViewModel("m", StubRepository { throw apiError(ApiException.GONE, "Este menú ya venció", 410) }, StubLocation(), tracker, StubReports())
        advanceUntilIdle()
        assertEquals(emptyList<RecordingTracker.Tracked>(), tracker.events)
    }

    @Test
    fun arrivalIsTrackedOnlyWhenConfirmedAndOnlyOnce() = runTest(dispatcher) {
        val tracker = RecordingTracker()
        val vm = MenuDetailViewModel("m", StubRepository { MenuDetailResponse(serverNow, serverNow, menu()) }, StubLocation(), tracker, StubReports()) { serverNow }
        advanceUntilIdle()

        vm.onArrivalAnswered(true)
        vm.onArrivalAnswered(true) // double tap

        assertEquals(listOf(EventKind.DETAIL_OPEN, EventKind.ARRIVAL), tracker.events.map { it.kind })
        assertEquals("arrival_prompt", tracker.events.last().source)
    }

    @Test
    fun notYetSendsNoArrival() = runTest(dispatcher) {
        val tracker = RecordingTracker()
        val vm = MenuDetailViewModel("m", StubRepository { MenuDetailResponse(serverNow, serverNow, menu()) }, StubLocation(), tracker, StubReports()) { serverNow }
        advanceUntilIdle()

        vm.onArrivalAnswered(false)

        assertEquals(listOf(EventKind.DETAIL_OPEN), tracker.events.map { it.kind })
    }

    @Test
    fun selectionIsTracked() = runTest(dispatcher) {
        val tracker = RecordingTracker()
        val vm = MenuDetailViewModel("m", StubRepository { MenuDetailResponse(serverNow, serverNow, menu()) }, StubLocation(), tracker, StubReports()) { serverNow }
        advanceUntilIdle()
        vm.onSelect()
        assertEquals(EventKind.SELECTION, tracker.events.last().kind)
    }

    private fun apiError(code: String, message: String, status: Int) = ApiException(ApiError(code, message), status)

    private fun menu() = DailyMenu(
        id = "m", title = "Tazón", validUntil = serverNow + Duration.ofHours(1), publishedAt = serverNow,
        establishmentId = "e", establishmentName = "Bowls", area = "Centro", lowestPriceCop = 12_000,
    )

    internal class StubRepository(private val detail: suspend () -> MenuDetailResponse) : MenuRepository {
        override suspend fun menu(id: String) = detail()
        override suspend fun feed(filters: FeedFilters, origin: String?): FeedResponse = error("unused")
        override suspend fun myMenus(): List<DailyMenu> = error("unused")
        override suspend fun publish(body: MenuBody): DailyMenu = error("unused")
        override suspend fun revise(id: String, body: MenuBody): DailyMenu = error("unused")
        override suspend fun close(id: String, expectedVersion: Int?): CloseResponse = error("unused")
    }
}
