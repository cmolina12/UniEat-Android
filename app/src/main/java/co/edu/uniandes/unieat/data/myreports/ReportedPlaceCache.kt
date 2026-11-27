package co.edu.uniandes.unieat.data.myreports

import androidx.collection.LruCache
import co.edu.uniandes.unieat.core.model.ReportedPlaceResponse
import java.time.Duration
import java.time.Instant

/**
 * Camilo, Sprint 4. Places of Mis reportes already opened, in memory, so going back and forth between
 * the list and a place does not ask the server again.
 *
 * LruCache keeps a LinkedHashMap in access order: a hit moves the entry to the end, and when a put goes
 * over [maxEntries] the entry at the front (least recently used) is evicted.
 *
 * - [maxEntries] = 10: the list only shows places where the student reported, and the server keeps their
 *   100 newest reports, so a student rarely has more than a handful. One entry is a few KB of text.
 * - [FRESH_FOR] = 60 s: a status only changes when an admin or the community resolves a report (minutes
 *   or hours), so reopening a place within a minute uses the cache and skips the request. Older entries
 *   are still drawn at once while a fresh copy is requested.
 *
 * Lives in AppContainer (one per process, like MenuDetailCache), so it survives the ViewModel of a place.
 * Emptied on sign-out.
 */
class ReportedPlaceCache(
    maxEntries: Int = MAX_ENTRIES,
    private val clock: () -> Instant = Instant::now,
) {
    data class Entry(val response: ReportedPlaceResponse, val receivedAt: Instant)

    private val lru = LruCache<String, Entry>(maxEntries)

    /** A hit also makes it the most recently used. */
    operator fun get(establishmentId: String): Entry? = lru[establishmentId]

    fun put(response: ReportedPlaceResponse) {
        lru.put(response.place.establishmentId, Entry(response, clock()))
    }

    fun isFresh(entry: Entry): Boolean = Duration.between(entry.receivedAt, clock()) < FRESH_FOR

    fun clear() = lru.evictAll()

    val size: Int get() = lru.size()

    companion object {
        const val MAX_ENTRIES = 10
        val FRESH_FOR: Duration = Duration.ofSeconds(60)
    }
}
