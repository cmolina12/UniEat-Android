package co.edu.uniandes.unieat.data.repository

import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.FeedResponse
import co.edu.uniandes.unieat.core.model.InstantSerializer
import co.edu.uniandes.unieat.core.model.UniEatJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Duration
import java.time.Instant

/** Last successful GET /feed per filter combination, for offline. */
@Serializable
data class CachedFeed(
    val filters: FeedFilters,
    val response: FeedResponse,
    @Serializable(InstantSerializer::class) val savedAt: Instant,
)

/**
 * Server time - device time for a saved copy. Keeps the offset from when it was saved, but never
 * goes before the server time at saving (if the phone clock was fixed in between).
 */
fun savedClockOffset(savedAt: Instant, serverNow: Instant, deviceNow: Instant): Duration {
    val estimated = deviceNow + Duration.between(savedAt, serverNow)
    return Duration.between(deviceNow, maxOf(estimated, serverNow))
}

/** One menu from a saved feed, with the clocks to judge its validity offline. */
data class SavedMenu(val menu: DailyMenu, val serverNow: Instant, val savedAt: Instant)

interface FeedCache {
    suspend fun save(entry: CachedFeed)

    suspend fun load(filters: FeedFilters): CachedFeed?

    /** Newest saved copy of menu id across all filters, for the detail offline. */
    suspend fun findMenu(id: String): SavedMenu?

    suspend fun clear()
}

/** One JSON file per filter combination, only the MAX_ENTRIES most recent. Cleared on sign-out. */
class FileFeedCache(private val dir: File) : FeedCache {
    private val mutex = Mutex()

    override suspend fun save(entry: CachedFeed) = io {
        dir.mkdirs()
        fileFor(entry.filters).writeText(UniEatJson.encodeToString(CachedFeed.serializer(), entry))
        dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(MAX_ENTRIES).forEach { it.delete() }
    }

    override suspend fun load(filters: FeedFilters): CachedFeed? = io {
        val file = fileFor(filters)
        try {
            if (!file.exists()) null
            else UniEatJson.decodeFromString(CachedFeed.serializer(), file.readText()).takeIf { it.filters == filters }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Newest saved copy of the menu. Files are visited newest first (the file's modified time is the moment
     * [save] wrote it) and the search stops at the first copy found, so a menu present in every saved feed
     * decodes one file instead of all of them. A file whose text does not contain the id is skipped without
     * being decoded: the id is always written verbatim in the JSON, so this can only skip files without it.
     */
    override suspend fun findMenu(id: String): SavedMenu? = io {
        dir.listFiles().orEmpty()
            .sortedByDescending { it.lastModified() }
            .firstNotNullOfOrNull { file ->
                try {
                    val text = file.readText()
                    if (!text.contains(id)) {
                        null
                    } else {
                        val entry = UniEatJson.decodeFromString(CachedFeed.serializer(), text)
                        entry.response.menus.firstOrNull { it.id == id }
                            ?.let { SavedMenu(it, entry.response.serverNow, entry.savedAt) }
                    }
                } catch (_: Exception) {
                    null
                }
            }
    }

    override suspend fun clear() = io { dir.deleteRecursively(); Unit }

    private fun fileFor(filters: FeedFilters) = File(dir, "feed-${filters.hashCode().toUInt().toString(16)}.json")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private companion object {
        const val MAX_ENTRIES = 8
    }
}
