package co.edu.uniandes.unieat.data.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class FeedLoadTelemetryTest {
    @Test fun `p95 uses nearest-rank percentile`() {
        assertEquals(95L, FeedLoadTelemetry.percentile95((1L..100L).toList()))
    }

    @Test fun `p95 is null without rendered samples`() {
        assertNull(FeedLoadTelemetry.percentile95(emptyList()))
    }

    @Test fun `report groups technical outcomes and excludes abandoned from failure rate`() {
        val now = Instant.parse("2026-10-02T12:00:00Z")
        val start = now.minusSeconds(120).toEpochMilli()
        fun record(id: String, outcome: String, renderedAfter: Long? = null) = FeedLoadRecord(
            loadId = id,
            sessionId = "s",
            startedAtEpochMs = start,
            requestCompletedAtEpochMs = start + 100,
            renderedAtEpochMs = renderedAfter?.let { start + it },
            outcome = outcome,
            connectionType = "wifi",
            deviceModel = "Pixel",
            osVersion = "Android 16",
            appVersion = "1",
        )
        val report = FeedLoadTelemetry.buildReport(
            listOf(record("ok", "rendered", 400), record("fail", "failure"), record("left", "render_pending")),
            7,
            now,
            ZoneOffset.UTC,
        )
        assertEquals(2, report.attempts)
        assertEquals(1, report.abandoned)
        assertEquals(1, report.groups.single().failures)
        assertEquals(1, report.groups.single().abandoned)
        assertEquals(0.5, report.groups.single().failureRate, 0.0)
        assertEquals(400L, report.groups.single().p95RequestToRenderMs)
    }
}
