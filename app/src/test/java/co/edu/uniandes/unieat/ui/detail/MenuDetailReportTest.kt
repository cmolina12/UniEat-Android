package co.edu.uniandes.unieat.ui.detail

import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import co.edu.uniandes.unieat.core.model.MenuReport
import co.edu.uniandes.unieat.core.model.ReportKind
import co.edu.uniandes.unieat.data.analytics.EventKind
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MenuDetailReportTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-09-29T17:00:00Z")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private var serverMenu = DailyMenu(
        id = "m", title = "Tazón", version = 2, validUntil = now + Duration.ofHours(1), publishedAt = now,
        establishmentId = "e", establishmentName = "Bowls", area = "Centro", lowestPriceCop = 12_000,
        latitude = 4.6036, longitude = -74.064,
    )
    private var menuCalls = 0
    private val reports = StubReports()
    private val tracker = RecordingTracker()

    private fun viewModel() = MenuDetailViewModel(
        "m",
        MenuDetailViewModelTest.StubRepository { menuCalls++; MenuDetailResponse(now, now, serverMenu) },
        StubLocation(),
        tracker,
        reports,
    ) { now }

    @Test
    fun sendsTheVersionOnScreenAndTrimsTheNote() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.submitReport(ReportKind.LOCATION, "  La entrada es por la otra calle  ", observedWaitMinutes = 25)
        advanceUntilIdle()

        val body = reports.sent.single()
        assertEquals("m", body.publicationId)
        assertEquals(2, body.version)
        assertEquals(ReportKind.LOCATION, body.kind)
        assertEquals("La entrada es por la otra calle", body.note)
        assertNull("minutes only travel with long_line", body.observedWaitMinutes)
    }

    @Test
    fun blankNoteIsOmittedAndMinutesGoWithLongLine() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.submitReport(ReportKind.LONG_LINE, "   ", observedWaitMinutes = 25)
        advanceUntilIdle()

        assertNull(reports.sent.single().note)
        assertEquals(25, reports.sent.single().observedWaitMinutes)
        assertEquals(ReportSubmission.Sent("Observación registrada. Gracias.", pending = false), vm.report.value)
    }

    @Test
    fun pendingLocationReportRefreshesTheCardWarning() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(0, (vm.state.value as MenuDetailUiState.Content).location.pendingLocationReports)

        serverMenu = serverMenu.copy(reports = listOf(MenuReport("r1", ReportKind.LOCATION, "pending", now)), pendingReports = 1)
        vm.submitReport(ReportKind.LOCATION, "", null)
        advanceUntilIdle()

        val content = vm.state.value as MenuDetailUiState.Content
        assertEquals(1, content.location.pendingLocationReports)
        assertTrue((vm.report.value as ReportSubmission.Sent).pending)
        assertEquals("initial load + one quiet refresh", 2, menuCalls)
    }

    @Test
    fun observationsDoNotReloadTheMenu() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.submitReport(ReportKind.ACCURATE, "", null)
        advanceUntilIdle()
        assertEquals(1, menuCalls)
    }

    @Test
    fun serverErrorsAreShownWithTheirSpanishMessage() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        reports.result = { throw ApiException(ApiError(ApiException.RATE_LIMITED, "Ya enviaste varios reportes. Intenta más tarde."), 429) }

        vm.submitReport(ReportKind.PRICE, "", null)
        advanceUntilIdle()

        assertEquals(ReportSubmission.Failed("Ya enviaste varios reportes. Intenta más tarde."), vm.report.value)
    }

    @Test
    fun offlineSaysTheReportWasNotSent() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        reports.result = { throw ApiException.offline() }

        vm.submitReport(ReportKind.PRICE, "", null)
        advanceUntilIdle()

        val failed = vm.report.value as ReportSubmission.Failed
        assertTrue(failed.message, failed.message.contains("no se envió"))
    }

    @Test
    fun doubleTapSendsOnce() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.submitReport(ReportKind.PRICE, "", null)
        vm.submitReport(ReportKind.PRICE, "", null)
        assertEquals(ReportSubmission.Sending, vm.report.value)
        advanceUntilIdle()

        assertEquals(1, reports.sent.size)
    }

    @Test
    fun reopeningTheSheetClearsTheLastResult() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.submitReport(ReportKind.ACCURATE, "", null)
        advanceUntilIdle()

        vm.onReportSheetOpened()

        assertEquals(ReportSubmission.Idle, vm.report.value)
    }

    @Test
    fun arrivalReportCountsAsTheVisitArrivalOnlyOnce() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onArrivalAnswered(true)
        vm.submitReport(ReportKind.ARRIVAL, "", null)
        advanceUntilIdle()

        assertEquals(1, tracker.events.count { it.kind == EventKind.ARRIVAL })
        assertEquals(ArrivalAnswer.CONFIRMED, vm.arrival.value)
    }

    @Test
    fun arrivalReportAloneTracksArrivalFromTheSheet() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.submitReport(ReportKind.ARRIVAL, "", null)
        advanceUntilIdle()

        assertEquals("report_sheet", tracker.events.single { it.kind == EventKind.ARRIVAL }.source)
    }
}
