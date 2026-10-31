package co.edu.uniandes.unieat.data.local

import co.edu.uniandes.unieat.core.model.InstantSerializer
import co.edu.uniandes.unieat.core.model.LocationCompletenessSnapshot
import co.edu.uniandes.unieat.core.model.LocationGuidanceSnapshot
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import co.edu.uniandes.unieat.core.model.UniEatJson
import co.edu.uniandes.unieat.data.pilot.CachePilotReport
import co.edu.uniandes.unieat.data.telemetry.FeedLoadReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Last dashboard for a role and period, for offline. */
@Serializable
data class SavedPerformance(
    val role: String,
    val days: Int,
    @Serializable(InstantSerializer::class) val savedAt: Instant,
    val summary: PerformanceSummary,
    val feedLoadReport: FeedLoadReport? = null,
    val ownEstablishmentsOnly: Boolean = false,
    val locationGuidance: LocationGuidanceSnapshot? = null,
    val locationCompleteness: LocationCompletenessSnapshot? = null,
    /** BQ-13 (Samuel). Default null, so copies saved before this field existed still decode. */
    val cachePilot: CachePilotReport? = null,
)

interface PerformanceSnapshots {
    suspend fun save(snapshot: SavedPerformance)

    suspend fun load(role: String, days: Int): SavedPerformance?

    suspend fun clear()
}

/** One JSON file per role and period. Temp file + move so a crash never leaves half a file. Cleared on sign-out. */
class FilePerformanceSnapshots(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PerformanceSnapshots {
    private val mutex = Mutex()

    override suspend fun save(snapshot: SavedPerformance) = withContext(io) {
        mutex.withLock {
            dir.mkdirs()
            val target = fileFor(snapshot.role, snapshot.days)
            val temp = File(dir, "${target.name}.tmp")
            temp.writeText(UniEatJson.encodeToString(SavedPerformance.serializer(), snapshot))
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Unit
        }
    }

    override suspend fun load(role: String, days: Int): SavedPerformance? = withContext(io) {
        mutex.withLock {
            val file = fileFor(role, days)
            try {
                if (!file.exists()) null
                else UniEatJson.decodeFromString(SavedPerformance.serializer(), file.readText())
                    .takeIf { it.role == role && it.days == days }
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun clear() = withContext(io) {
        mutex.withLock {
            dir.listFiles().orEmpty().forEach { it.delete() }
        }
    }

    private fun fileFor(role: String, days: Int) = File(dir, "performance-$role-$days.json")
}
