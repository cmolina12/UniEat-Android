package co.edu.uniandes.unieat.data.telemetry

import co.edu.uniandes.unieat.data.analytics.FlushResult
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** BQ-02: the screen name, the crash rate per 1,000 sessions, and the uploader. */
class CrashTelemetryTest {
    private val now = Instant.parse("2026-11-23T15:00:00Z")

    @Test fun `a route becomes the simple screen name`() {
        assertEquals("Feed", CrashTelemetry.screenName("co.edu.uniandes.unieat.ui.navigation.Feed"))
        assertEquals("Detail", CrashTelemetry.screenName("co.edu.uniandes.unieat.ui.navigation.Detail/{id}"))
        assertEquals("Saved", CrashTelemetry.screenName("co.edu.uniandes.unieat.ui.navigation.Saved?x={x}"))
        assertNull(CrashTelemetry.screenName(null))
        assertNull(CrashTelemetry.screenName(""))
    }

    @Test fun `rate is crashes per 1000 sessions that visited the screen on the same device`() {
        val crashes = listOf(crash("c1", screen = "Detail", action = "selection"), crash("c2", screen = "Detail", action = "selection"))
        // 8 sessions visited Detail on this phone, 2 visited only Feed, 5 visited Detail on another phone.
        val sessions = List(8) { session("s$it", listOf("Feed", "Detail")) } +
            List(2) { session("f$it", listOf("Feed")) } +
            List(5) { session("o$it", listOf("Detail"), device = "Pixel 7") }

        val report = CrashTelemetry.buildReport(crashes, sessions, days = 7, now = now)

        val group = report.groups.single()
        assertEquals(2, group.crashes)
        assertEquals(8, group.sessionsVisitingScreen)
        assertEquals(250.0, group.crashesPer1000Sessions, 0.001)
        assertEquals(2, report.crashes)
        assertEquals(15, report.sessions)
    }

    @Test fun `groups are split by action and ranked by rate then by crashes`() {
        val crashes = listOf(
            crash("a", screen = "Feed", action = "screen_open"),
            crash("b", screen = "Detail", action = "location_open"),
            crash("c", screen = "Detail", action = "selection"),
            crash("d", screen = "Detail", action = "selection"),
        )
        // 10 sessions visited Feed and Detail, 10 more visited only Feed.
        val sessions = List(10) { session("s$it", listOf("Feed", "Detail")) } + List(10) { session("f$it", listOf("Feed")) }

        val groups = CrashTelemetry.buildReport(crashes, sessions, 7, now).groups

        assertEquals(listOf("selection", "location_open", "screen_open"), groups.map { it.precedingAction })
        assertEquals(listOf(200.0, 100.0, 50.0), groups.map { it.crashesPer1000Sessions })
    }

    @Test fun `never fewer sessions than crashes even if the crashed session was not saved`() {
        val report = CrashTelemetry.buildReport(listOf(crash("a")), emptyList(), 7, now)

        assertEquals(1, report.groups.single().sessionsVisitingScreen)
        assertEquals(1000.0, report.groups.single().crashesPer1000Sessions, 0.001)
    }

    @Test fun `records older than the period are ignored`() {
        val old = crash("old", at = now - Duration.ofDays(8))
        val oldSession = session("old", listOf("Detail"), at = now - Duration.ofDays(8))

        val report = CrashTelemetry.buildReport(listOf(old), listOf(oldSession), 7, now)

        assertTrue(report.groups.isEmpty())
        assertEquals(0, report.crashes)
        assertEquals(0, report.sessions)
    }

    @Test fun `uploader sends crashes and sessions together and marks them`() = runTest {
        val outbox = FakeCrashOutbox(listOf(crash("a")), listOf(session("s1", listOf("Feed"))))
        val repo = FakeCrashRepository()

        assertEquals(FlushResult.DONE, CrashUploader(outbox, repo).flush())

        assertEquals(1, repo.batches.size)
        assertEquals(1, repo.batches.single().crashes.size)
        assertEquals(1, repo.batches.single().sessions.size)
        assertTrue(outbox.isEmpty())
    }

    @Test fun `offline keeps the records for a later retry`() = runTest {
        val outbox = FakeCrashOutbox(listOf(crash("a")), emptyList())
        val repo = FakeCrashRepository(failure = ApiException.offline())

        assertEquals(FlushResult.RETRY_LATER, CrashUploader(outbox, repo).flush())

        assertTrue(!outbox.isEmpty())
    }

    @Test fun `a batch the server rejects as invalid is dropped so it cannot block newer ones`() = runTest {
        val outbox = FakeCrashOutbox(listOf(crash("a")), emptyList())
        val repo = FakeCrashRepository(failure = ApiException(ApiError(ApiException.VALIDATION_ERROR, "bad"), 400))

        assertEquals(FlushResult.DONE, CrashUploader(outbox, repo).flush())

        assertTrue(outbox.isEmpty())
    }

    @Test fun `nothing pending is done without calling the server`() = runTest {
        val repo = FakeCrashRepository()

        assertEquals(FlushResult.DONE, CrashUploader(FakeCrashOutbox(emptyList(), emptyList()), repo).flush())

        assertTrue(repo.batches.isEmpty())
    }

    private fun crash(
        id: String,
        screen: String = "Detail",
        action: String = "selection",
        device: String = "Galaxy A14",
        at: Instant = now - Duration.ofHours(1),
    ) = CrashRecord(
        crashId = id, sessionId = "s-$id", occurredAtEpochMs = at.toEpochMilli(), screen = screen, precedingAction = action,
        exceptionType = "java.lang.IllegalStateException", deviceModel = device, osVersion = "Android 14 (SDK 34)", appVersion = "1.0",
    )

    private fun session(
        id: String,
        screens: List<String>,
        device: String = "Galaxy A14",
        at: Instant = now - Duration.ofHours(2),
    ) = SessionVisitRecord(
        sessionId = id, startedAtEpochMs = at.toEpochMilli(), screens = screens,
        deviceModel = device, osVersion = "Android 14 (SDK 34)", appVersion = "1.0",
    )

    private class FakeCrashOutbox(crashes: List<CrashRecord>, sessions: List<SessionVisitRecord>) : CrashOutbox {
        private var crashes = crashes
        private var sessions = sessions

        fun isEmpty() = crashes.isEmpty() && sessions.isEmpty()

        override fun pendingUpload(limit: Int) = CrashBatch(crashes.take(limit), sessions.take(limit))

        override fun markUploaded(batch: CrashBatch) {
            val crashIds = batch.crashes.map { it.crashId }.toSet()
            val sessionIds = batch.sessions.map { it.sessionId }.toSet()
            crashes = crashes.filter { it.crashId !in crashIds }
            sessions = sessions.filter { it.sessionId !in sessionIds }
        }
    }

    private class FakeCrashRepository(private val failure: ApiException? = null) : CrashRepository {
        val batches = mutableListOf<CrashBatch>()

        override suspend fun upload(batch: CrashBatch) {
            failure?.let { throw it }
            batches += batch
        }

        override suspend fun summary(days: Int) = CrashReport(days, 0, 0)
    }
}
