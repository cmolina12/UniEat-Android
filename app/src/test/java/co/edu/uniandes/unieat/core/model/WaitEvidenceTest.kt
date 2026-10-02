package co.edu.uniandes.unieat.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** BQ-06 evidence rule (queue-v1): at least 3 samples and the newest within 30 minutes. */
class WaitEvidenceTest {

    private val now = Instant.parse("2026-10-01T17:00:00Z")

    @Test
    fun threeRecentSamplesGiveEvidence() {
        assertTrue(menu(waitMinutes = 9, samples = 3, newestAgo = Duration.ofMinutes(10)).hasWaitEvidence(now))
    }

    @Test
    fun fewerThanThreeSamplesGiveNoEvidence() {
        assertFalse(menu(waitMinutes = 9, samples = 2, newestAgo = Duration.ofMinutes(10)).hasWaitEvidence(now))
    }

    @Test
    fun staleNewestReportGivesNoEvidence() {
        assertFalse(menu(waitMinutes = 9, samples = 4, newestAgo = Duration.ofMinutes(31)).hasWaitEvidence(now))
    }

    @Test
    fun exactlyThirtyMinutesStillCounts() {
        assertTrue(menu(waitMinutes = 9, samples = 4, newestAgo = Duration.ofMinutes(30)).hasWaitEvidence(now))
    }

    @Test
    fun missingWaitMinutesGivesNoEvidence() {
        assertFalse(menu(waitMinutes = null, samples = 4, newestAgo = Duration.ofMinutes(5)).hasWaitEvidence(now))
    }

    private fun menu(waitMinutes: Int?, samples: Int, newestAgo: Duration) = DailyMenu(
        id = "m", title = "Tazón", validUntil = now + Duration.ofHours(1), publishedAt = now - Duration.ofHours(1),
        establishmentId = "e", establishmentName = "Bowls", area = "Centro", lowestPriceCop = 12_000,
        waitMinutes = waitMinutes, waitSampleCount = samples, waitNewestReportAt = now - newestAgo,
    )
}
