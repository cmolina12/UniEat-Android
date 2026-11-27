package co.edu.uniandes.unieat.data.myreports

import co.edu.uniandes.unieat.core.model.MyReport
import co.edu.uniandes.unieat.core.model.ReportedPlace
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ReportHistoryStoreTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-27T17:00:00Z")

    @Test
    fun anEmptyTableMeansNeverLoadedNotNoReports() = runTest(dispatcher) {
        assertNull(ReportHistoryStore(MemoryDao(), dispatcher) { now }.loadPlaces())
    }

    @Test
    fun theListAndAPlaceComeBackAsTheyWereSaved() = runTest(dispatcher) {
        val store = ReportHistoryStore(MemoryDao(), dispatcher) { now }
        val place = place("a")
        store.savePlaces(listOf(place))
        store.savePlace(place, listOf(report("r1"), report("r2")))

        val saved = store.loadPlaces()!!
        assertEquals(listOf(place), saved.places)
        assertEquals(now, saved.savedAt)
        assertEquals(listOf("r1", "r2"), store.loadPlace("a")!!.reports.map { it.id })
    }

    @Test
    fun aPlaceThatLeftTheListTakesItsReportsWithIt() = runTest(dispatcher) {
        val dao = MemoryDao()
        val store = ReportHistoryStore(dao, dispatcher) { now }
        store.savePlaces(listOf(place("a"), place("b")))
        store.savePlace(place("a"), listOf(report("r1")))

        store.savePlaces(listOf(place("b")))

        assertNull(store.loadPlace("a"))
        assertEquals(0, dao.reports.size)
    }

    @Test
    fun signingOutEmptiesBothTables() = runTest(dispatcher) {
        val dao = MemoryDao()
        val store = ReportHistoryStore(dao, dispatcher) { now }
        store.savePlaces(listOf(place("a")))
        store.savePlace(place("a"), listOf(report("r1")))

        store.clear()

        assertNull(store.loadPlaces())
        assertEquals(0, dao.reports.size)
    }

    private fun place(id: String) = ReportedPlace(
        establishmentId = id, name = "Local $id", area = "Centro", total = 1, pending = 1, lastReportAt = now,
    )

    private fun report(id: String) = MyReport(
        id = id, kind = "price", publicationId = "p", version = 1, menuTitle = "Almuerzo", note = "subió",
        status = "pending", observedAt = now,
    )

    /** Same queries as Room, over lists. The @Transaction defaults of the interface run unchanged. */
    class MemoryDao : ReportHistoryDao {
        val places = mutableListOf<ReportedPlaceRow>()
        val reports = mutableListOf<MyReportRow>()

        override suspend fun places() = places.sortedByDescending { it.lastReportAtMillis }
        override suspend fun place(establishmentId: String) = places.firstOrNull { it.establishmentId == establishmentId }
        override suspend fun reports(establishmentId: String) =
            reports.filter { it.establishmentId == establishmentId }.sortedByDescending { it.observedAtMillis }
        override suspend fun insertPlaces(rows: List<ReportedPlaceRow>) { places += rows }
        override suspend fun insertReports(rows: List<MyReportRow>) { reports += rows }
        override suspend fun deletePlaces() = places.clear()
        override suspend fun deleteReportsOf(establishmentId: String) { reports.removeAll { it.establishmentId == establishmentId } }
        override suspend fun deleteOrphanReports() {
            reports.removeAll { report -> places.none { it.establishmentId == report.establishmentId } }
        }
        override suspend fun deleteReports() = reports.clear()
    }
}
