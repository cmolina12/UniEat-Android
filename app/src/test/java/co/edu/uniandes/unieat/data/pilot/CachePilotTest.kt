package co.edu.uniandes.unieat.data.pilot

import co.edu.uniandes.unieat.data.analytics.EventKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Samuel — BQ-13: assignment, consent and how one visit is recorded. */
@OptIn(ExperimentalCoroutinesApi::class)
class CachePilotTest {
    private val dispatcher = StandardTestDispatcher()
    private val start = Instant.parse("2026-10-30T17:00:00Z")
    private var now = start
    private val dao = InMemoryCachePilotDao()
    private val enrollment = MemoryEnrollment()
    private var user: String? = PilotUsers.cacheEnabled
    private var ids = 0
    private var visitsEnded = 0

    private fun pilot() = CachePilot(
        enrollment, dao, { user }, { "wifi" }, clock = { now }, io = dispatcher,
        newSessionId = { "visit-${ids++}" }, onVisitEnded = { visitsEnded++ },
    )

    private val t0 = start.toEpochMilli()

    @Test
    fun assignmentIsStableAndSplitsUsersInTwoGroups() {
        val groups = (0 until 400).map { PilotVariant.assign("student-$it") }
        assertEquals(PilotVariant.assign("student-7"), PilotVariant.assign("student-7"))
        val cache = groups.count { it == PilotVariant.CACHE_ENABLED }
        assertTrue("roughly half each, got $cache of 400", cache in 140..260)
    }

    /** Reference values computed with md5 outside the app; the backend SQL must give the same groups. */
    @Test
    fun assignmentMatchesTheMd5RuleTheServerUses() {
        assertEquals(PilotVariant.ONLINE_ONLY, PilotVariant.assign("4f6c8a8e-0000-4000-8000-000000000001"))
        assertEquals(PilotVariant.CACHE_ENABLED, PilotVariant.assign("4f6c8a8e-0000-4000-8000-000000000002"))
        assertEquals(PilotVariant.CACHE_ENABLED, PilotVariant.assign("4f6c8a8e-0000-4000-8000-000000000003"))
    }

    @Test
    fun nobodyIsInThePilotUntilTheyJoin() = runTest(dispatcher) {
        val pilot = pilot()
        assertNull(pilot.variant())
        assertFalse(pilot.status().joined)

        assertTrue(pilot.join())

        assertEquals(PilotVariant.CACHE_ENABLED, pilot.variant())
        assertEquals(PilotStatus(signedIn = true, joined = true, variant = PilotVariant.CACHE_ENABLED), pilot.status())
    }

    @Test
    fun anotherAccountOnTheSamePhoneIsNotEnrolled() = runTest(dispatcher) {
        val pilot = pilot()
        pilot.join()
        user = PilotUsers.onlineOnly
        assertNull(pilot.variant())
    }

    @Test
    fun withoutAUserThereIsNothingToJoin() = runTest(dispatcher) {
        user = null
        val pilot = pilot()
        assertFalse(pilot.join())
        assertFalse(pilot.status().signedIn)
    }

    @Test
    fun aCacheFirstVisitRecordsTheCopyThenTheNetwork() = runTest(dispatcher) {
        val pilot = pilot()
        pilot.onLoadStarted("load-1", PilotVariant.CACHE_ENABLED, t0, cacheAgeMs = 600_000)
        pilot.onContentDrawn("load-1", ContentSource.CACHE, t0 + 120)
        pilot.onContentDrawn("load-1", ContentSource.NETWORK, t0 + 1_900)

        val visit = dao.rows.values.single()
        assertTrue(visit.cacheAvailable)
        assertEquals(600_000L, visit.cacheAgeMs)
        assertEquals(120L, visit.firstContentMs)
        assertEquals("cache", visit.firstContentSource)
        assertEquals(1_900L, visit.networkRenderMs)
        assertEquals(CachePilot.OUTCOME_NETWORK, visit.outcome)
        assertEquals("wifi", visit.connectionType)
        assertFalse(visit.closed)
    }

    @Test
    fun onlyTheFirstLoadOfTheVisitSetsTheMetrics() = runTest(dispatcher) {
        val pilot = pilot()
        pilot.onLoadStarted("load-1", PilotVariant.ONLINE_ONLY, t0, cacheAgeMs = null)
        pilot.onContentDrawn("load-1", ContentSource.NETWORK, t0 + 2_000)
        // A shake reload a minute later: same visit, its faster render does not replace the first one.
        pilot.onLoadStarted("load-2", PilotVariant.ONLINE_ONLY, t0 + 60_000, cacheAgeMs = 60_000)
        pilot.onContentDrawn("load-2", ContentSource.NETWORK, t0 + 60_300)

        val visit = dao.rows.values.single()
        assertEquals(2_000L, visit.firstContentMs)
        assertFalse("availability is the one at the start of the visit", visit.cacheAvailable)
        assertEquals(t0 + 60_000, visit.lastActivityMillis)
    }

    @Test
    fun failuresKeepWhetherTheCopyStayedOnScreen() = runTest(dispatcher) {
        val pilot = pilot()
        pilot.onLoadStarted("offline", PilotVariant.CACHE_ENABLED, t0, cacheAgeMs = 1_000)
        pilot.onContentDrawn("offline", ContentSource.CACHE, t0 + 90)
        pilot.onLoadFailed("offline", showedSavedCopy = true)
        assertEquals(CachePilot.OUTCOME_CACHE_ONLY, dao.rows.values.single().outcome)

        now = start.plusSeconds(3_600) // more than 30 idle minutes: the next load is a new visit
        pilot.onLoadStarted("nothing", PilotVariant.ONLINE_ONLY, now.toEpochMilli(), cacheAgeMs = 1_000)
        pilot.onLoadFailed("nothing", showedSavedCopy = false)

        val (first, second) = dao.rows.values.toList()
        assertTrue(first.closed)
        assertEquals(CachePilot.OUTCOME_FAILURE, second.outcome)
        assertNull(second.firstContentMs)
        assertEquals(1, visitsEnded)
    }

    @Test
    fun aSelectionMarksTheVisitButNotAfterThirtyIdleMinutes() = runTest(dispatcher) {
        val pilot = pilot()
        pilot.onLoadStarted("load", PilotVariant.CACHE_ENABLED, t0, null)
        pilot.onInteraction(EventKind.DETAIL_OPEN, t0 + 20_000)
        assertFalse(dao.rows.values.single().selected)

        pilot.onInteraction(EventKind.SELECTION, t0 + 40_000)
        assertTrue(dao.rows.values.single().selected)

        val other = pilot()
        other.onLoadStarted("late", PilotVariant.CACHE_ENABLED, t0, null)
        other.onInteraction(EventKind.SELECTION, t0 + CachePilot.IDLE_MILLIS + 1)
        assertFalse(dao.rows.values.last().selected)
    }

    @Test
    fun visitsOfAPreviousLaunchEndAsAbandonedWhenTheyWereStillPending() = runTest(dispatcher) {
        // The app died while the first request was on its way.
        pilot().onLoadStarted("load", PilotVariant.CACHE_ENABLED, t0, null)

        pilot().closeFinishedSessions() // a new process: it has no live visit of its own

        val visit = dao.rows.values.single()
        assertTrue(visit.closed)
        assertEquals(CachePilot.OUTCOME_ABANDONED, visit.outcome)
    }

    @Test
    fun theLiveVisitStaysOpenUntilItIsIdle() = runTest(dispatcher) {
        val pilot = pilot()
        pilot.onLoadStarted("load", PilotVariant.CACHE_ENABLED, t0, null)

        now = start.plusSeconds(60)
        pilot.closeFinishedSessions()
        assertFalse(dao.rows.values.single().closed)

        now = start.plusMillis(CachePilot.IDLE_MILLIS)
        pilot.closeFinishedSessions()
        assertTrue(dao.rows.values.single().closed)
    }

    @Test
    fun changingGroupStartsANewVisit() = runTest(dispatcher) {
        val pilot = pilot()
        pilot.onLoadStarted("a", PilotVariant.CACHE_ENABLED, t0, null)
        pilot.onLoadStarted("b", PilotVariant.ONLINE_ONLY, t0 + 1_000, null)

        assertEquals(listOf(true, false), dao.rows.values.map { it.closed })
        assertEquals("online_only", dao.rows.values.last().variant)
    }

    @Test
    fun leavingDeletesWhatWasNotSentAndStopsRecording() = runTest(dispatcher) {
        val pilot = pilot()
        pilot.join()
        pilot.onLoadStarted("a", PilotVariant.CACHE_ENABLED, t0, null)
        dao.upsert(dao.rows.values.single().copy(sessionId = "sent", closed = true, uploaded = true))

        pilot.leave()

        assertEquals(listOf("sent"), dao.rows.keys.toList())
        assertNull(pilot.variant())
        pilot.onContentDrawn("a", ContentSource.CACHE, t0 + 10)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun signingOutClearsEverything() = runTest(dispatcher) {
        val pilot = pilot()
        pilot.join()
        pilot.onLoadStarted("a", PilotVariant.CACHE_ENABLED, t0, null)

        pilot.clear()

        assertTrue(dao.rows.isEmpty())
        assertNull(enrollment.user)
    }

    @Test
    fun theLocalReportOnlyCountsEndedVisits() = runTest(dispatcher) {
        val pilot = pilot()
        pilot.onLoadStarted("a", PilotVariant.CACHE_ENABLED, t0, null)
        assertEquals(0, pilot.report(7).sessions)

        now = start.plusMillis(CachePilot.IDLE_MILLIS)
        pilot.closeFinishedSessions()

        val report = pilot.report(7)
        assertEquals(1, report.sessions)
        assertEquals("this_device", report.scope)
    }
}
