package co.edu.uniandes.unieat.data.analytics

import co.edu.uniandes.unieat.core.model.AdminDashboardResponse
import co.edu.uniandes.unieat.core.model.BatchResponse
import co.edu.uniandes.unieat.core.model.EventBatch
import co.edu.uniandes.unieat.core.model.LocationGuidanceSnapshot
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import co.edu.uniandes.unieat.core.model.RemoteEvent
import co.edu.uniandes.unieat.data.remote.ApiClient
import co.edu.uniandes.unieat.data.remote.ApiException
import io.ktor.http.HttpMethod

/** Analytics endpoints behind the Repository pattern (callers never see ApiClient). */
interface AnalyticsRepository {
    suspend fun sendBatch(events: List<RemoteEvent>): BatchResponse

    suspend fun performance(days: Int): PerformanceSummary

    suspend fun restaurantPerformance(days: Int): PerformanceSummary = performance(days)

    suspend fun locationGuidance(days: Int): LocationGuidanceSnapshot? = null

    suspend fun restaurantLocationGuidance(days: Int): LocationGuidanceSnapshot? = null
}

class RemoteAnalyticsRepository(private val api: ApiClient) : AnalyticsRepository {
    override suspend fun sendBatch(events: List<RemoteEvent>): BatchResponse =
        api.send(HttpMethod.Post, "events/batch", EventBatch(events))

    override suspend fun performance(days: Int): PerformanceSummary =
        api.get("performance", mapOf("days" to days, "platform" to ALL_PLATFORMS))

    override suspend fun restaurantPerformance(days: Int): PerformanceSummary =
        api.get("restaurant/performance", mapOf("days" to days, "platform" to ALL_PLATFORMS))

    override suspend fun locationGuidance(days: Int): LocationGuidanceSnapshot? =
        api.get<AdminDashboardResponse>("admin/dashboard", mapOf("days" to days)).bq05

    override suspend fun restaurantLocationGuidance(days: Int): LocationGuidanceSnapshot =
        api.get("restaurant/location-guidance", mapOf("days" to days))

    private companion object {
        const val ALL_PLATFORMS = "all"
    }
}

enum class FlushResult { DONE, RETRY_LATER }

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
                api.sendBatch(batch)
                queue.remove(ids)
            } catch (e: ApiException) {
                when (e.code) {
                    ApiException.VALIDATION_ERROR -> queue.remove(ids)
                    else -> return FlushResult.RETRY_LATER
                }
            }
        }
    }
}
