package co.edu.uniandes.unieat.data.analytics

import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.RemoteEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/** Event kinds accepted by POST /events/batch (api-v1 catalog). */
enum class EventKind(val wireName: String) {
    FEED_IMPRESSION("feed_impression"),
    DETAIL_OPEN("detail_open"),
    SELECTION("selection"),
    ARRIVAL("arrival"),
}

/**
 * Analytics entry point for every screen. [track] returns immediately and never throws:
 * events are queued on disk and uploaded in the background when there is network.
 */
interface EventTracker {
    /** [screen] and [source] go to `metadata` (the server keeps only screen, position, source, appVersion). */
    fun track(kind: EventKind, publicationId: String, version: Int, screen: String, source: String? = null)
}

/** Tracks against the exact menu version the student saw. */
fun EventTracker.track(kind: EventKind, menu: DailyMenu, screen: String, source: String? = null) =
    track(kind, menu.id, menu.version, screen, source)

/** Asks the background uploader to run (implemented with WorkManager). */
fun interface FlushScheduler {
    fun schedule()
}

/**
 * [EventTracker] that persists each event in [queue] and schedules an upload.
 * `eventId` is generated here, once, so retries never count an event twice (the server dedupes by it).
 */
class QueuedEventTracker(
    private val queue: EventQueue,
    private val scheduler: FlushScheduler,
    private val scope: CoroutineScope,
    private val appVersion: String,
    /** One per app process, like iOS AppStore.sessionID. */
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
