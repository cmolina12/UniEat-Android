package co.edu.uniandes.unieat.data.repository

import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

class FileFeedCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private val now = Instant.parse("2026-10-08T17:00:00Z")
    private val menu = DailyMenu(
        id = "a", title = "Menú a", validUntil = now + Duration.ofHours(1),
        publishedAt = now - Duration.ofHours(1), establishmentId = "e",
        establishmentName = "Local a", area = "Centro", lowestPriceCop = 12_000,
    )

    @Test fun `saved feed is read back for the same filters only`() = runTest {
        val cache = FileFeedCache(folder.root)
        val entry = CachedFeed(FeedFilters(), FeedResponse(now, now, menus = listOf(menu)), savedAt = now)

        cache.save(entry)

        assertEquals(entry, cache.load(FeedFilters()))
        assertNull(cache.load(FeedFilters(area = "Sur")))
    }

    @Test fun `clear removes every saved feed`() = runTest {
        val cache = FileFeedCache(folder.root)
        cache.save(CachedFeed(FeedFilters(), FeedResponse(now, now, menus = listOf(menu)), savedAt = now))

        cache.clear()

        assertNull(cache.load(FeedFilters()))
    }

    @Test fun `saved offset is kept while the phone clock is unchanged`() {
        // Phone 90 s behind the server when saving, read 10 min later.
        val savedAt = now - Duration.ofSeconds(90)
        assertEquals(
            Duration.ofSeconds(90),
            savedClockOffset(savedAt, serverNow = now, deviceNow = savedAt + Duration.ofMinutes(10)),
        )
    }

    @Test fun `a phone clock corrected after saving never moves time before the save`() {
        // Saved while the phone was 20 h ahead; read after the clock was fixed.
        val savedAt = now + Duration.ofHours(20)
        val deviceNow = now + Duration.ofMinutes(5)
        val offset = savedClockOffset(savedAt, serverNow = now, deviceNow = deviceNow)
        assertEquals(now, deviceNow + offset)
    }

    @Test fun `findMenu returns the newest saved copy of a menu`() = runTest {
        val cache = FileFeedCache(folder.root)
        cache.save(CachedFeed(FeedFilters(area = "Sur"), FeedResponse(now, now, menus = listOf(menu)), savedAt = now))
        val newer = menu.copy(title = "Nuevo")
        cache.save(CachedFeed(FeedFilters(), FeedResponse(now, now, menus = listOf(newer)), savedAt = now + Duration.ofMinutes(5)))

        assertEquals("Nuevo", cache.findMenu("a")?.menu?.title)
        assertNull(cache.findMenu("missing"))
    }

    @Test fun `a corrupt file is treated as no copy`() = runTest {
        val cache = FileFeedCache(folder.root)
        cache.save(CachedFeed(FeedFilters(), FeedResponse(now, now, menus = listOf(menu)), savedAt = now))
        folder.root.listFiles()!!.single().writeText("{ not json")

        assertNull(cache.load(FeedFilters()))
    }
}
