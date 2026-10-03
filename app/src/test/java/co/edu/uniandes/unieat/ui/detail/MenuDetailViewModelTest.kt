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
import org.junit.Assert.assertFalse
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
            serverNow - Duration.ofSeconds(90)
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
        val offline = vm.state.value as MenuDetailUiState.Error
        assertEquals(ApiException.OFFLINE, offline.code)
        assertEquals(MenuDetailViewModel.OFFLINE_MESSAGE, offline.message)
        assertFalse(offline.message.contains("copia"))

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
        vm.load()
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
        vm.onArrivalAnswered(true)

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
    fun openingMapsTracksLocationOpenEveryTime() = runTest(dispatcher) {
        val tracker = RecordingTracker()
        val vm = MenuDetailViewModel("m", StubRepository { MenuDetailResponse(serverNow, serverNow, menu()) }, StubLocation(), tracker, StubReports()) { serverNow }
        advanceUntilIdle()

        vm.onOpenMaps("pin")
        vm.onOpenMaps("address")

        val opens = tracker.events.filter { it.kind == EventKind.LOCATION_OPEN }
        assertEquals(listOf("pin", "address"), opens.map { it.source })
        assertEquals("location_open", EventKind.LOCATION_OPEN.wireName)
    }

    @Test
    fun authRequiredBecomesSessionExpiredAndTracksNothing() = runTest(dispatcher) {
        val tracker = RecordingTracker()
        val vm = MenuDetailViewModel("m", StubRepository {
            throw apiError(ApiException.AUTH_REQUIRED, "La sesión no es válida o venció.", 401)
        }, StubLocation(), tracker, StubReports())
        advanceUntilIdle()

        assertEquals(MenuDetailUiState.SessionExpired, vm.state.value)
        assertEquals(emptyList<RecordingTracker.Tracked>(), tracker.events)
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
        override suspend fun feed(filters: FeedFilters): FeedResponse = error("unused")
        override suspend fun myMenus(): List<DailyMenu> = error("unused")
        override suspend fun publish(body: MenuBody): DailyMenu = error("unused")
        override suspend fun revise(id: String, body: MenuBody): DailyMenu = error("unused")
        override suspend fun close(id: String, expectedVersion: Int?): CloseResponse = error("unused")
    }
}
