package co.edu.uniandes.unieat.data.analytics

import co.edu.uniandes.unieat.core.model.RemoteEvent
import co.edu.uniandes.unieat.core.model.UniEatJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant

/**
 * Events waiting for signal, on disk. One JSON object per line (JSON Lines): adding an event appends one
 * line instead of rewriting the whole queue, which offline can hold up to [maxEvents]. The file is rewritten
 * only when events leave (upload, trim, expiry).
 */
class EventQueue(
    private val file: File,
    /** The old format (one JSON array). Read once, rewritten as lines and deleted. */
    private val legacyFile: File? = null,
    private val maxEvents: Int = 1_000,
    private val maxAge: Duration = Duration.ofDays(7).minusHours(1),
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Wraps the disk writes so they show in a system trace (Perfetto). Tests leave it as a plain call. */
    private val trace: (String, () -> Unit) -> Unit = { _, block -> block() },
) {
    private val mutex = Mutex()
    private val serializer = ListSerializer(RemoteEvent.serializer())
    private var cache: MutableList<RemoteEvent>? = null

    suspend fun add(event: RemoteEvent) = locked { events ->
        events += event
        if (events.size > maxEvents) {
            while (events.size > maxEvents) events.removeAt(0)
            save(events)
        } else {
            append(event)
        }
    }

    suspend fun peek(limit: Int): List<RemoteEvent> = locked { it.take(limit) }

    suspend fun remove(eventIds: Set<String>) = mutate { events -> events.removeAll { it.eventId in eventIds } }

    suspend fun size(): Int = locked { it.size }

    private suspend fun <T> locked(block: (MutableList<RemoteEvent>) -> T): T =
        withContext(io) { mutex.withLock { block(load()) } }

    private suspend fun mutate(block: (MutableList<RemoteEvent>) -> Unit) = locked { events ->
        block(events)
        save(events)
    }

    private fun load(): MutableList<RemoteEvent> {
        val events = cache ?: read().also { cache = it }
        val cutoff = clock() - maxAge
        if (events.removeAll { it.occurredAt < cutoff }) save(events)
        return events
    }

    private fun read(): MutableList<RemoteEvent> {
        if (!file.exists()) return readLegacy()
        val events = mutableListOf<RemoteEvent>()
        var damaged = false
        for (line in file.readLines()) {
            if (line.isBlank()) continue
            val event = decode(line)
            if (event == null) damaged = true else events += event
        }
        // a line cut by a crash mid-append: drop it now, or the next append would glue onto it
        if (damaged) save(events)
        return events
    }

    private fun decode(line: String): RemoteEvent? = try {
        UniEatJson.decodeFromString(RemoteEvent.serializer(), line)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Queue saved by an older version as one JSON array: keep its events in the new format. */
    private fun readLegacy(): MutableList<RemoteEvent> {
        val legacy = legacyFile?.takeIf { it.exists() } ?: return mutableListOf()
        val events = try {
            UniEatJson.decodeFromString(serializer, legacy.readText()).toMutableList()
        } catch (e: SerializationException) {
            mutableListOf()
        } catch (e: IllegalArgumentException) {
            mutableListOf()
        }
        save(events)
        legacy.delete()
        return events
    }

    /** One line at the end of the file: the cost no longer grows with the queue. */
    private fun append(event: RemoteEvent) = trace(TRACE_WRITE) {
        try {
            file.parentFile?.mkdirs()
            file.appendText(UniEatJson.encodeToString(RemoteEvent.serializer(), event) + "\n")
        } catch (e: IOException) {
        }
    }

    private fun save(events: List<RemoteEvent>) = trace(TRACE_WRITE) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(events.joinToString("") { UniEatJson.encodeToString(RemoteEvent.serializer(), it) + "\n" })
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
        }
    }

    companion object {
        const val TRACE_WRITE = "EventQueue.write"
    }
}
