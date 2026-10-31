package co.edu.uniandes.unieat.data.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Samuel — BQ-13: how ended visits become the comparison table. */
class CachePilotReportTest {
    private var n = 0

    private fun visit(
        variant: PilotVariant,
        firstContentMs: Long?,
        selected: Boolean,
        connection: String = "wifi",
        hadCopy: Boolean = true,
        closed: Boolean = true,
    ) = CachePilotSession(
        sessionId = "v${n++}",
        variant = variant.wireName,
        startedAtMillis = 0,
        lastActivityMillis = 0,
        connectionType = connection,
        cacheAvailable = hadCopy,
        firstContentMs = firstContentMs,
        selected = selected,
        closed = closed,
    )

    @Test
    fun nearestRankReturnsARealMeasurement() {
        val sorted = (1L..100L).toList()
        assertEquals(50L, CachePilotReport.nearestRank(sorted, 0.50))
        assertEquals(95L, CachePilotReport.nearestRank(sorted, 0.95))
        assertEquals(200L, CachePilotReport.nearestRank(listOf(100L, 200L, 300L, 400L), 0.50))
        assertNull(CachePilotReport.nearestRank(emptyList(), 0.5))
    }

    @Test
    fun groupsAreComparedInsideTheSameConnectionAndCopyCondition() {
        val report = CachePilotReport.build(
            listOf(
                visit(PilotVariant.CACHE_ENABLED, 120, selected = true),
                visit(PilotVariant.ONLINE_ONLY, 1_800, selected = false),
                visit(PilotVariant.ONLINE_ONLY, 900, selected = true, connection = "cellular"),
                visit(PilotVariant.CACHE_ENABLED, 2_100, selected = true, hadCopy = false),
            ),
            days = 7,
        )

        assertEquals(4, report.sessions)
        assertEquals(
            listOf("cellular/true/online_only", "wifi/true/cache_enabled", "wifi/true/online_only", "wifi/false/cache_enabled"),
            report.groups.map { "${it.connectionType}/${it.cacheAvailable}/${it.variant}" },
        )
    }

    @Test
    fun timesIgnoreVisitsThatNeverShowedMenus() {
        val report = CachePilotReport.build(
            listOf(
                visit(PilotVariant.ONLINE_ONLY, 1_000, selected = true),
                visit(PilotVariant.ONLINE_ONLY, 3_000, selected = true),
                visit(PilotVariant.ONLINE_ONLY, null, selected = false), // offline, nothing on screen
            ),
            days = 7,
        )

        val group = report.groups.single()
        assertEquals(3, group.sessions)
        assertEquals(2, group.withContent)
        assertEquals(1_000L, group.medianFirstContentMs)
        assertEquals(3_000L, group.p95FirstContentMs)
        assertEquals(1, group.withoutSelection)
    }

    @Test
    fun theShareAppearsOnlyFromFiveVisits() {
        val four = List(4) { visit(PilotVariant.CACHE_ENABLED, 100, selected = it == 0) }
        assertNull(CachePilotReport.build(four, 7).groups.single().withoutSelectionShare)

        val five = four + visit(PilotVariant.CACHE_ENABLED, 100, selected = false)
        assertEquals(0.8, CachePilotReport.build(five, 7).groups.single().withoutSelectionShare!!, 1e-9)
    }

    @Test
    fun openVisitsAreNotCountedYet() {
        val report = CachePilotReport.build(listOf(visit(PilotVariant.CACHE_ENABLED, 100, selected = false, closed = false)), 7)
        assertEquals(0, report.sessions)
        assertEquals(emptyList<CachePilotGroup>(), report.groups)
    }
}
