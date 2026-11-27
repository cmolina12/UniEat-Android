package co.edu.uniandes.unieat.ui.myreports

import co.edu.uniandes.unieat.core.model.MyReport
import co.edu.uniandes.unieat.core.model.ReportFilter
import co.edu.uniandes.unieat.core.model.ReportKind
import co.edu.uniandes.unieat.core.model.ReportedPlace
import co.edu.uniandes.unieat.core.model.ReportedPlaceResponse
import co.edu.uniandes.unieat.core.model.ReportedPlacesResponse
import co.edu.uniandes.unieat.data.myreports.MyReportsRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MyReportsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-27T17:00:00Z")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadsThePlacesAndTheTotals() = runTest(dispatcher) {
        val vm = MyReportsViewModel(Repo(places = listOf(place("a", pending = 1, confirmed = 2), place("b", total = 1))))
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(listOf("a", "b"), state.places.map { it.establishmentId })
        assertEquals(4, state.totalReports)
        assertEquals(2, state.totalConfirmed)
    }

    @Test
    fun theFilterOnlyHidesRowsAndTheChipsCountPlaces() = runTest(dispatcher) {
        val repo = Repo(places = listOf(place("a", pending = 1), place("b", confirmed = 1), place("c", total = 1)))
        val vm = MyReportsViewModel(repo)
        advanceUntilIdle()

        vm.selectFilter(ReportFilter.IN_REVIEW)
        assertEquals(listOf("a"), vm.state.value.visible.map { it.establishmentId })
        assertEquals(3, vm.state.value.count(ReportFilter.ALL))
        assertEquals(1, vm.state.value.count(ReportFilter.RESOLVED))
        // observations ("c") have nothing to follow, they only show under Todos
        vm.selectFilter(ReportFilter.RESOLVED)
        assertEquals(listOf("b"), vm.state.value.visible.map { it.establishmentId })
        assertEquals(1, repo.calls)
    }

    @Test
    fun anExpiredSessionIsReported() = runTest(dispatcher) {
        val vm = MyReportsViewModel(Repo(error = ApiException.AUTH_REQUIRED))
        advanceUntilIdle()

        assertTrue(vm.state.value.sessionExpired)
    }

    @Test
    fun anErrorKeepsItsMessageAndRetryClearsIt() = runTest(dispatcher) {
        val repo = Repo(error = ApiException.INTERNAL_ERROR)
        val vm = MyReportsViewModel(repo)
        advanceUntilIdle()
        assertEquals("falló", vm.state.value.error)

        repo.error = null
        vm.load()
        advanceUntilIdle()
        assertNull(vm.state.value.error)
    }

    @Test
    fun theDetailListsTheReportsOfOnePlace() = runTest(dispatcher) {
        val vm = ReportedPlaceViewModel("a", Repo(places = listOf(place("a", total = 1))))
        advanceUntilIdle()

        assertEquals("a", vm.state.value.place?.establishmentId)
        assertEquals(listOf("r-a"), vm.state.value.reports.map { it.id })
    }

    @Test
    fun theWireNameFindsTheSameLabelAsTheReportSheet() {
        assertEquals(ReportKind.NOT_FOUND, reportKindOf("not_found"))
        assertEquals(ReportKind.LONG_LINE, reportKindOf("long_line"))
        assertNull(reportKindOf("something_new"))
        assertEquals("No encontré el local", report("not_found").kindTitle)
        assertEquals("Reporte", report("something_new").kindTitle)
        assertEquals("Confirmado por la comunidad", report("price", "confirmed", "community").statusTitle)
    }

    private fun place(id: String, total: Int? = null, pending: Int = 0, confirmed: Int = 0) = ReportedPlace(
        establishmentId = id, name = "Local $id", area = "Centro",
        total = total ?: (pending + confirmed), pending = pending, confirmed = confirmed, lastReportAt = now,
    )

    private fun report(kind: String, status: String = "observation", source: String? = null) = MyReport(
        id = "r", kind = kind, publicationId = "p", version = 1, menuTitle = "Almuerzo",
        status = status, resolutionSource = source, observedAt = now,
    )

    private inner class Repo(
        private val places: List<ReportedPlace> = emptyList(),
        var error: String? = null,
    ) : MyReportsRepository {
        var calls = 0

        override suspend fun places(): ReportedPlacesResponse {
            calls++
            error?.let { throw ApiException(ApiError(it, "falló")) }
            return ReportedPlacesResponse(now, places)
        }

        override suspend fun place(establishmentId: String): ReportedPlaceResponse {
            error?.let { throw ApiException(ApiError(it, "falló")) }
            val place = places.first { it.establishmentId == establishmentId }
            return ReportedPlaceResponse(now, place, listOf(report("price").copy(id = "r-$establishmentId")))
        }
    }
}
