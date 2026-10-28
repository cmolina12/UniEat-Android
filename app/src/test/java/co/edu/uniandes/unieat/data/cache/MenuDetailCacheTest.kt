package co.edu.uniandes.unieat.data.cache

import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** The LRU rule itself: what stays, what is evicted and in which order. */
class MenuDetailCacheTest {
    private val now = Instant.parse("2026-10-28T17:00:00Z")

    @Test
    fun whenFullTheLeastRecentlyUsedMenuIsEvicted() {
        val cache = MenuDetailCache(maxEntries = 3) { now }
        cache.put("a", detail("a"))
        cache.put("b", detail("b"))
        cache.put("c", detail("c"))

        cache.put("d", detail("d"))

        assertNull(cache["a"]) // the oldest one left
        assertEquals(listOf("b", "c", "d"), cache.keysFromLeastRecent())
        assertEquals(3, cache.size)
    }

    @Test
    fun readingAMenuMakesItTheMostRecentSoItSurvives() {
        val cache = MenuDetailCache(maxEntries = 3) { now }
        cache.put("a", detail("a"))
        cache.put("b", detail("b"))
        cache.put("c", detail("c"))

        assertNotNull(cache["a"]) // the student went back to menu "a"
        cache.put("d", detail("d"))

        assertNull(cache["b"]) // now "b" was the least recently used
        assertEquals(listOf("c", "a", "d"), cache.keysFromLeastRecent())
    }

    @Test
    fun anEntryRemembersWhenItArrived() {
        val cache = MenuDetailCache { now }
        cache.put("a", detail("a"))

        val entry = cache["a"]!!
        assertEquals(now, entry.receivedAt)
        assertEquals("a", entry.response.menu.id)
    }

    @Test
    fun removeAndClear() {
        val cache = MenuDetailCache { now }
        cache.put("a", detail("a"))
        cache.put("b", detail("b"))

        cache.remove("a")
        assertNull(cache["a"])

        cache.clear()
        assertEquals(0, cache.size)
    }

    @Test
    fun theDefaultSizeIsTwentyMenus() {
        val cache = MenuDetailCache { now }
        (1..25).forEach { cache.put("m$it", detail("m$it")) }

        assertEquals(MenuDetailCache.MAX_ENTRIES, cache.size)
        assertNull(cache["m5"])
        assertNotNull(cache["m6"])
    }

    private fun detail(id: String) = MenuDetailResponse(
        serverNow = now, fetchedAt = now,
        menu = DailyMenu(
            id = id, title = "Menú $id", validUntil = now.plusSeconds(3600), publishedAt = now,
            establishmentId = "e", establishmentName = "Ajonjolí", area = "Centro", lowestPriceCop = 12_000,
        ),
    )
}
