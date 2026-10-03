package co.edu.uniandes.unieat.ui.feed

import co.edu.uniandes.unieat.core.model.DailyMenu
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RecommendationStrategySamuelTest {
    private val now = Instant.parse("2026-10-02T12:00:00Z")

    @Test fun `fastest wait keeps supported shortest wait first`() {
        val menus = listOf(menu("a", 15, 3), menu("b", 5, 4), menu("c", null, 0))
        assertEquals("b", FastestWaitStrategy.pick(menus, 0, now)?.id)
        assertEquals("a", FastestWaitStrategy.pick(menus, 1, now)?.id)
    }

    @Test fun `unsupported wait never outranks supported evidence`() {
        val menus = listOf(menu("old", 1, 1), menu("supported", 12, 3))
        assertEquals("supported", FastestWaitStrategy.pick(menus, 0, now)?.id)
    }

    private fun menu(id: String, wait: Int?, samples: Int): DailyMenu = DailyMenu(
        id = id,
        title = id,
        validUntil = now.plus(Duration.ofHours(1)),
        publishedAt = now.minus(Duration.ofHours(1)),
        establishmentId = "e",
        establishmentName = id,
        area = "Centro",
        lowestPriceCop = 10_000,
        waitMinutes = wait,
        waitSampleCount = samples,
        waitNewestReportAt = if (wait == null) null else now.minus(Duration.ofMinutes(5)),
    )
}
