package co.edu.uniandes.unieat.data.myreports

import co.edu.uniandes.unieat.core.model.ReportedPlace
import co.edu.uniandes.unieat.core.model.ReportedPlaceResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ReportedPlaceCacheTest {
    private var now = Instant.parse("2026-11-27T17:00:00Z")

    @Test
    fun overTheLimitTheLeastRecentlyOpenedPlaceLeaves() {
        val cache = ReportedPlaceCache(maxEntries = 2) { now }
        cache.put(response("a"))
        cache.put(response("b"))
        cache["a"] // reopened: now "b" is the least recently used

        cache.put(response("c"))

        assertNotNull(cache["a"])
        assertNull(cache["b"])
        assertEquals(2, cache.size)
    }

    @Test
    fun anEntryIsFreshForOneMinute() {
        val cache = ReportedPlaceCache { now }
        cache.put(response("a"))
        val entry = cache["a"]!!

        now += Duration.ofSeconds(59)
        assertTrue(cache.isFresh(entry))
        now += Duration.ofSeconds(1)
        assertFalse(cache.isFresh(entry))
    }

    @Test
    fun signingOutEmptiesIt() {
        val cache = ReportedPlaceCache { now }
        cache.put(response("a"))
        cache.clear()
        assertEquals(0, cache.size)
    }

    private fun response(id: String) = ReportedPlaceResponse(
        now,
        ReportedPlace(establishmentId = id, name = "Local $id", area = "Centro", lastReportAt = now),
        emptyList(),
    )
}
