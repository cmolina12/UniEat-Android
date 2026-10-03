package co.edu.uniandes.unieat.data.fake

import co.edu.uniandes.unieat.core.decision.locationGuidance
import co.edu.uniandes.unieat.core.model.ReportBody
import co.edu.uniandes.unieat.core.model.ReportKind
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

class FakeReportLoopTest {
    @Test
    fun locationReportBecomesAPendingWarningAndRateLimitApplies() = runTest {
        var now = Instant.parse("2026-09-29T17:00:00Z")
        val menus = FakeMenuRepository(latencyMillis = 0, clock = { now })
        val reports = FakeReportRepository(latencyMillis = 0, clock = { now })
        val ajiaco = SeedMenus.AJIACO
        val before = locationGuidance(menus.menu(ajiaco).menu).pendingLocationReports

        val response = reports.submit(ReportBody(ajiaco, 1, ReportKind.LOCATION))
        assertEquals("pending", response.status)
        reports.submit(ReportBody(ajiaco, 1, ReportKind.ACCURATE))

        val after = menus.menu(ajiaco).menu
        assertEquals(before + 1, locationGuidance(after).pendingLocationReports)
        assertEquals(after.reports.count { it.status == "pending" }, after.pendingReports)

        reports.submit(ReportBody(ajiaco, 1, ReportKind.PRICE))
        try {
            reports.submit(ReportBody(ajiaco, 1, ReportKind.PRICE))
            fail("expected 429")
        } catch (e: ApiException) {
            assertEquals(ApiException.RATE_LIMITED, e.code)
        }
    }
}
