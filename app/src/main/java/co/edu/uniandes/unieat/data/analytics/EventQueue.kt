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
 * Pending analytics events, persisted as JSON in app-private storage so they survive the app
 * being closed while offline. Plain JVM code (java.io / java.nio), so it is unit-tested with a temp folder.
 *
 * Bounded on purpose: at most [maxEvents] (oldest dropped first), and events older than [maxAge]
 * are discarded because api-v1 rejects `occurredAt` more than 7 days old.
 */
class EventQueue(
    private val file: File,
    private val maxEvents: Int = 1_000,
    private val maxAge: Duration = Duration.ofDays(7).minusHours(1),
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val serializer = ListSerializer(RemoteEvent.serializer())
    private var cache: MutableList<RemoteEvent>? = null

    suspend fun add(event: RemoteEvent) = mutate { events ->
        events += event
        while (events.size > maxEvents) events.removeAt(0)
    }

    /** The oldest [limit] events, without removing them (removed only after the server confirms). */
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

    private fun read(): MutableList<RemoteEvent> = try {
        if (file.exists()) UniEatJson.decodeFromString(serializer, file.readText()).toMutableList() else mutableListOf()
    } catch (e: SerializationException) {
        mutableListOf() // corrupted file: start over rather than crash on every launch
    } catch (e: IllegalArgumentException) {
        mutableListOf()
    }

    /** Write to a temp file, then rename: a crash mid-write never leaves half a JSON file. */
    private fun save(events: List<RemoteEvent>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(UniEatJson.encodeToString(serializer, events))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // Disk full or similar: the in-memory copy still uploads during this session.
        }
    }
}
