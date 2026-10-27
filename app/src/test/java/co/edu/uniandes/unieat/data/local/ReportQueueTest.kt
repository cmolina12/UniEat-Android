package co.edu.uniandes.unieat.data.local

import co.edu.uniandes.unieat.core.model.ReportBody
import co.edu.uniandes.unieat.core.model.ReportKind
import co.edu.uniandes.unieat.core.model.ReportResponse
import co.edu.uniandes.unieat.data.analytics.FlushResult
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.ReportRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ReportQueueTest {
    private val dispatcher = StandardTestDispatcher()
    private var now = Instant.parse("2026-10-27T15:00:00Z")
    private val dao = InMemoryReportDao()
    private val sent = mutableListOf<ReportBody>()
    private var failure: ((ReportBody) -> ApiException?) = { null }
    private var schedules = 0

    private val reports = object : ReportRepository {
        override suspend fun submit(body: ReportBody): ReportResponse {
            failure(body)?.let { throw it }
            sent += body
            return ReportResponse("r", body.publicationId, body.version, body.kind, "observation", now, "ok")
        }
    }

    private val queue = ReportQueue(dao, reports, { schedules++ }, clock = { now }, io = dispatcher)

    private fun body(kind: ReportKind = ReportKind.NOT_FOUND, menu: String = "m1") = ReportBody(menu, 1, kind)

    private fun error(code: String) = ApiException(ApiError(code, code), 0)

    @Test
    fun enqueueSavesTheReportAndSchedulesTheUpload() = runTest(dispatcher) {
        queue.enqueue(body())

        assertEquals(1, dao.rows.value.size)
        assertEquals(1, schedules)
        assertEquals(1, queue.pendingFor("m1").first())
        assertEquals(0, queue.pendingFor("other").first())
    }

    @Test
    fun flushSendsOldestFirstAndEmptiesTheTable() = runTest(dispatcher) {
        queue.enqueue(body(ReportKind.NOT_FOUND))
        now += Duration.ofMinutes(1)
        queue.enqueue(body(ReportKind.ARRIVAL))

        assertEquals(FlushResult.DONE, queue.flush())

        assertEquals(listOf(ReportKind.NOT_FOUND, ReportKind.ARRIVAL), sent.map { it.kind })
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun stillOfflineKeepsEverythingForTheNextAttempt() = runTest(dispatcher) {
        queue.enqueue(body())
        failure = { error(ApiException.OFFLINE) }

        assertEquals(FlushResult.RETRY_LATER, queue.flush())

        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun aReportTheServerWillNeverAcceptIsDroppedAndTheRestStillGo() = runTest(dispatcher) {
        queue.enqueue(body(menu = "gone"))
        now += Duration.ofMinutes(1)
        queue.enqueue(body(menu = "m1"))
        failure = { if (it.publicationId == "gone") error(ApiException.NOT_FOUND) else null }

        assertEquals(FlushResult.DONE, queue.flush())

        assertEquals(listOf("m1"), sent.map { it.publicationId })
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun serverErrorsCountAttemptsAndGiveUpAfterTheLimit() = runTest(dispatcher) {
        queue.enqueue(body())
        failure = { error(ApiException.INTERNAL_ERROR) }

        repeat(ReportQueue.MAX_ATTEMPTS - 1) { assertEquals(FlushResult.RETRY_LATER, queue.flush()) }
        assertEquals(ReportQueue.MAX_ATTEMPTS - 1, dao.rows.value.single().attempts)

        queue.flush()
        assertTrue("dropped after ${ReportQueue.MAX_ATTEMPTS} failures", dao.rows.value.isEmpty())
    }

    @Test
    fun reportsOlderThanADayAreNotSent() = runTest(dispatcher) {
        queue.enqueue(body())
        now += Duration.ofHours(25)

        assertEquals(FlushResult.DONE, queue.flush())

        assertTrue(sent.isEmpty())
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun theTableNeverGrowsPastItsLimit() = runTest(dispatcher) {
        repeat(ReportQueue.MAX_ROWS + 5) {
            queue.enqueue(body())
            now += Duration.ofSeconds(1)
        }

        assertEquals(ReportQueue.MAX_ROWS, dao.rows.value.size)
    }
}

/** Same behavior as the SQL in [PendingReportDao], over a list in memory. */
internal class InMemoryReportDao : PendingReportDao {
    val rows = MutableStateFlow(emptyList<PendingReport>())
    private var nextId = 1L

    override suspend fun insert(report: PendingReport): Long {
        val id = nextId++
        rows.value = rows.value + report.copy(id = id)
        return id
    }

    override suspend fun oldest(limit: Int) = rows.value.sortedBy { it.createdAtMillis }.take(limit)

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun markFailedAttempt(id: Long) {
        rows.value = rows.value.map { if (it.id == id) it.copy(attempts = it.attempts + 1) else it }
    }

    override suspend fun deleteOlderThan(cutoffMillis: Long) {
        rows.value = rows.value.filterNot { it.createdAtMillis < cutoffMillis }
    }

    override suspend fun trimTo(keep: Int) {
        val kept = rows.value.sortedByDescending { it.createdAtMillis }.take(keep).map { it.id }.toSet()
        rows.value = rows.value.filter { it.id in kept }
    }

    override fun countFor(publicationId: String): Flow<Int> = rows.map { list -> list.count { it.publicationId == publicationId } }

    override suspend fun clear() {
        rows.value = emptyList()
    }
}
