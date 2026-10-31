package co.edu.uniandes.unieat.data.cache

import androidx.collection.LruCache
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import java.time.Instant

/**
 * Recently opened menu details in memory (cache then network). Students go back and forth
 * between a few menus before choosing, so a menu opened again shows at once.
 *
 * LruCache: when full it evicts the least recently used entry. Inside it's a LinkedHashMap
 * in access order, and its methods are synchronized so any thread can use it.
 *
 * 20 entries: a detail is a few KB, so 20 is way under 1 MB and covers the menus someone
 * compares in one lunch decision. Smaller evicts menus still being compared, bigger is wasted memory.
 */
class MenuDetailCache(
    maxEntries: Int = MAX_ENTRIES,
    private val clock: () -> Instant = Instant::now,
) {
    /** The response and when it arrived (for the server clock offset and the "saved at" time). */
    data class Entry(val response: MenuDetailResponse, val receivedAt: Instant)

    private val lru = LruCache<String, Entry>(maxEntries)

    /** A hit also makes it the most recently used. */
    operator fun get(menuId: String): Entry? = lru[menuId]

    fun put(menuId: String, response: MenuDetailResponse) {
        lru.put(menuId, Entry(response, clock()))
    }

    /** Closed or expired on the server, don't show it again. */
    fun remove(menuId: String) {
        lru.remove(menuId)
    }

    /** Memory only. Emptied on sign-out like the other copies. */
    fun clear() = lru.evictAll()

    val size: Int get() = lru.size()

    /** Least recent first (tests use it to check the order). */
    fun keysFromLeastRecent(): List<String> = lru.snapshot().keys.toList()

    companion object {
        const val MAX_ENTRIES = 20
    }
}
