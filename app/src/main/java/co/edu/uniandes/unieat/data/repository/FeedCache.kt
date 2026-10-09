package co.edu.uniandes.unieat.data.repository

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
import java.time.Instant

/** Last successful GET /feed per filter combination, shown when the device is offline. */
@Serializable
data class CachedFeed(
    val filters: FeedFilters,
    val response: FeedResponse,
    @Serializable(InstantSerializer::class) val savedAt: Instant,
)

interface FeedCache {
    suspend fun save(entry: CachedFeed)

    suspend fun load(filters: FeedFilters): CachedFeed?

    suspend fun clear()
}

/**
 * One JSON file per filter combination. The copy belongs to the signed-in user,
 * so [clear] runs on sign-out. Only the [MAX_ENTRIES] most recent combinations are kept.
 */
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

    override suspend fun clear() = io { dir.deleteRecursively(); Unit }

    private fun fileFor(filters: FeedFilters) = File(dir, "feed-${filters.hashCode().toUInt().toString(16)}.json")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private companion object {
        const val MAX_ENTRIES = 8
    }
}
