package co.edu.uniandes.unieat.data.analytics

import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.RemoteEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

enum class EventKind(val wireName: String) {
    FEED_IMPRESSION("feed_impression"),
    DETAIL_OPEN("detail_open"),
    SELECTION("selection"),
    ARRIVAL("arrival"),
    LOCATION_OPEN("location_open"),
}

interface EventTracker {
    fun track(kind: EventKind, publicationId: String, version: Int, screen: String, source: String? = null)
}

fun EventTracker.track(kind: EventKind, menu: DailyMenu, screen: String, source: String? = null) =
    track(kind, menu.id, menu.version, screen, source)

fun interface FlushScheduler {
    fun schedule()
}

class QueuedEventTracker(
    private val queue: EventQueue,
    private val scheduler: FlushScheduler,
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val sessionId: String = UUID.randomUUID().toString(),
    private val clock: () -> Instant = Instant::now,
    private val newEventId: () -> String = { UUID.randomUUID().toString() },
) : EventTracker {
    override fun track(kind: EventKind, publicationId: String, version: Int, screen: String, source: String?) {
        val event = RemoteEvent(
            eventId = newEventId(),
            sessionId = sessionId,
            publicationId = publicationId,
            version = version,
            kind = kind.wireName,
            occurredAt = clock(),
            metadata = buildJsonObject {
                put("screen", screen)
                if (source != null) put("source", source)
                put("appVersion", appVersion)
            },
        )
        scope.launch {
            queue.add(event)
            scheduler.schedule()
        }
    }
}
