package co.edu.uniandes.unieat.data.local

import co.edu.uniandes.unieat.core.model.InstantSerializer
import co.edu.uniandes.unieat.core.model.UniEatJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/** A value read from disk and when it was saved, so the screen can say "guardado a las HH:MM". */
@Serializable
data class Saved<T>(
    @Serializable(InstantSerializer::class) val savedAt: Instant,
    val value: T,
)

/**
 * Small JSON copies for the restaurant and admin screens: the last list that loaded (shown offline)
 * and the forms not sent yet (drafts). Everything belongs to the signed-in account, so [clear] runs on sign-out.
 */
interface OfflineStore {
    suspend fun <T> save(key: String, serializer: KSerializer<T>, value: T)

    /** null when nothing was saved under [key] or the file can no longer be read. */
    suspend fun <T> load(key: String, serializer: KSerializer<T>): Saved<T>?

    suspend fun remove(key: String)

    suspend fun clear()
}

/** One file per key. Written to a temporary file and then moved over the old one, like the Rendimiento copies. */
class FileOfflineStore(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Instant = Instant::now,
) : OfflineStore {
    private val mutex = Mutex()

    override suspend fun <T> save(key: String, serializer: KSerializer<T>, value: T) = withContext(io) {
        mutex.withLock {
            dir.mkdirs()
            val target = fileFor(key)
            val temp = File(dir, "${target.name}.tmp")
            temp.writeText(UniEatJson.encodeToString(Saved.serializer(serializer), Saved(clock(), value)))
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Unit
        }
    }

    override suspend fun <T> load(key: String, serializer: KSerializer<T>): Saved<T>? = withContext(io) {
        mutex.withLock {
            val file = fileFor(key)
            try {
                if (file.exists()) UniEatJson.decodeFromString(Saved.serializer(serializer), file.readText()) else null
            } catch (_: Exception) {
                // A damaged file, or one from an older version of the model, counts as "nothing saved".
                null
            }
        }
    }

    override suspend fun remove(key: String) = withContext(io) {
        mutex.withLock { fileFor(key).delete(); Unit }
    }

    override suspend fun clear() = withContext(io) {
        mutex.withLock { dir.listFiles().orEmpty().forEach { it.delete() } }
    }

    private fun fileFor(key: String) = File(dir, "$key.json")
}
