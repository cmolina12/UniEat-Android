package co.edu.uniandes.unieat.data.analytics

import co.edu.uniandes.unieat.core.model.BatchResponse
import co.edu.uniandes.unieat.core.model.EventBatch
import co.edu.uniandes.unieat.core.model.RemoteEvent
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiException
import io.ktor.http.HttpMethod

/** POST /events/batch behind the Repository pattern (the uploader never sees ApiClient). */
interface AnalyticsRepository {
    suspend fun sendBatch(events: List<RemoteEvent>): BatchResponse
}

class RemoteAnalyticsRepository(private val api: ApiClient) : AnalyticsRepository {
    override suspend fun sendBatch(events: List<RemoteEvent>): BatchResponse =
        api.send(HttpMethod.Post, "events/batch", EventBatch(events)) // platform = "android" by default
}

enum class FlushResult { DONE, RETRY_LATER }

/** Drains [EventQueue] in batches of at most 100 (the api-v1 limit). Called from the WorkManager worker. */
class EventUploader(
    private val queue: EventQueue,
    private val api: AnalyticsRepository,
    private val batchSize: Int = 100,
) {
    suspend fun flush(): FlushResult {
        while (true) {
            val batch = queue.peek(batchSize)
            if (batch.isEmpty()) return FlushResult.DONE
            val ids = batch.mapTo(mutableSetOf()) { it.eventId }
            try {
                // 200 means every event is settled: accepted, duplicate (already counted) or rejected
                // for good (e.g. UNKNOWN_VERSION). None of them should be sent again.
                api.sendBatch(batch)
                queue.remove(ids)
            } catch (e: ApiException) {
                when (e.code) {
                    // The batch itself is malformed; retrying would fail forever and block newer events.
                    ApiException.VALIDATION_ERROR -> queue.remove(ids)
                    // OFFLINE, RATE_LIMITED, AUTH_REQUIRED, 5xx...: keep everything and try again later.
                    else -> return FlushResult.RETRY_LATER
                }
            }
        }
    }
}
