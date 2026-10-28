package co.edu.uniandes.unieat.data.cache

import androidx.collection.LruCache
import co.edu.uniandes.unieat.core.model.MenuDetailResponse
import java.time.Instant

/**
 * In-memory cache of the menu details opened recently. A student compares a few menus before choosing,
 * going back and forth between the feed and the detail; with this cache a menu opened again appears at
 * once, and the network only refreshes it (cache then network).
 *
 * LruCache = a map with a size limit that evicts the Least Recently Used entry when it is full. Inside
 * it is a LinkedHashMap in access order: every get/put moves the entry to the "most recent" end, and
 * evictions take the other end. All its methods are synchronized, so it is safe from any thread.
 *
 * Size: [maxEntries] counts entries (sizeOf is 1 per detail). One detail is a few KB of objects (title,
 * ~10 dishes, reports), so 20 of them stay far below 1 MB, a tiny part of the app's heap, and cover the
 * menus a student actually compares during one lunch decision. Much smaller would evict menus the
 * student is still comparing; much larger only holds memory nobody returns to.
 */
class MenuDetailCache(
    maxEntries: Int = MAX_ENTRIES,
    private val clock: () -> Instant = Instant::now,
) {
    /** The response and when the phone received it, to compute the server clock offset and say how old it is. */
    data class Entry(val response: MenuDetailResponse, val receivedAt: Instant)

    private val lru = LruCache<String, Entry>(maxEntries)

    /** A hit also marks the menu as the most recently used one. */
    operator fun get(menuId: String): Entry? = lru[menuId]

    fun put(menuId: String, response: MenuDetailResponse) {
        lru.put(menuId, Entry(response, clock()))
    }

    /** The publication is gone (closed or expired on the server): it must not be shown again. */
    fun remove(menuId: String) {
        lru.remove(menuId)
    }

    /** Memory only: emptied on sign-out like the other copies. */
    fun clear() = lru.evictAll()

    val size: Int get() = lru.size()

    /** Most recently used last, as LinkedHashMap keeps them (used by the tests to show the order). */
    fun keysFromLeastRecent(): List<String> = lru.snapshot().keys.toList()

    companion object {
        const val MAX_ENTRIES = 20
    }
}
