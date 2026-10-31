package co.edu.uniandes.unieat.data.local

import co.edu.uniandes.unieat.core.model.ReportBody
import co.edu.uniandes.unieat.data.analytics.FlushResult
import co.edu.uniandes.unieat.data.analytics.FlushScheduler
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.ReportRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** Reports made offline wait in Room and go out later, oldest first. enqueue saves one and schedules flush. */
class ReportQueue(
    private val dao: PendingReportDao,
    private val reports: ReportRepository,
    private val scheduler: FlushScheduler,
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Saves on IO (disk) and schedules the upload. */
    suspend fun enqueue(body: ReportBody) {
        withContext(io) {
            dao.insert(PendingReport.from(body, clock().toEpochMilli()))
            dao.trimTo(MAX_ROWS)
        }
        scheduler.schedule()
    }

    fun pendingFor(publicationId: String): Flow<Int> = dao.countFor(publicationId)

    /**
     * Sends everything waiting. Stops at the first one worth retrying later (no internet, session,
     * rate limit, server error) to keep the order. Drops the ones the server will never accept.
     */
    suspend fun flush(): FlushResult = withContext(io) {
        dao.deleteOlderThan((clock() - MAX_AGE).toEpochMilli())
        var batch = dao.oldest(BATCH_SIZE)
        while (batch.isNotEmpty()) {
            for (report in batch) {
                if (!send(report)) return@withContext FlushResult.RETRY_LATER
            }
            batch = dao.oldest(BATCH_SIZE)
        }
        FlushResult.DONE
    }

    /** true = left the queue (sent or dropped), false = try again later. */
    private suspend fun send(report: PendingReport): Boolean {
        try {
            reports.submit(report.toBody())
            dao.delete(report.id)
            return true
        } catch (e: ApiException) {
            return when (e.code) {
                // version gone, own menu, or invalid body: retrying won't help
                ApiException.VALIDATION_ERROR, ApiException.NOT_FOUND, ApiException.FORBIDDEN, ApiException.GONE -> {
                    dao.delete(report.id)
                    true
                }
                ApiException.INTERNAL_ERROR -> {
                    // one that keeps breaking the server can't block the rest forever
                    if (report.attempts + 1 >= MAX_ATTEMPTS) dao.delete(report.id) else dao.markFailedAttempt(report.id)
                    false
                }
                // no internet, expired session, rate limit: same report next time
                else -> false
            }
        }
    }

    /** Belongs to the signed-in student, emptied on sign-out. */
    suspend fun clear() = withContext(io) { dao.clear() }

    companion object {
        /** A menu lasts 24 h max, an older report no longer matches what the student saw. */
        val MAX_AGE: Duration = Duration.ofHours(24)
        const val MAX_ROWS = 50
        const val MAX_ATTEMPTS = 5
        private const val BATCH_SIZE = 20
    }
}
