package co.edu.uniandes.unieat.data.fake

import android.util.Log
import co.edu.uniandes.unieat.core.model.BatchResponse
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import co.edu.uniandes.unieat.core.model.RemoteEvent
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository

/**
 * Debug stand-in for POST /events/batch while there is no login: prints each batch to Logcat
 * (`adb logcat -s UniEatEvents`) and accepts it. The queue and WorkManager in front of it are the real ones.
 */
class LoggingAnalyticsRepository : AnalyticsRepository {
    override suspend fun sendBatch(events: List<RemoteEvent>): BatchResponse {
        events.forEach { Log.i(TAG, "${it.kind} ${it.publicationId} v${it.version} ${it.occurredAt} ${it.metadata} id=${it.eventId}") }
        Log.i(TAG, "batch sent: ${events.size} event(s)")
        return BatchResponse(accepted = events.size)
    }

    /** Demo numbers for the dashboard; real metrics come from GET /performance. */
    override suspend fun performance(days: Int): PerformanceSummary {
        val scale = if (days >= 28) 4 else 1
        return PerformanceSummary(
            periodDays = days,
            impressions = 48 * scale,
            detailOpens = 21 * scale,
            selections = 9 * scale,
            reportedArrivals = 5 * scale,
            sampleSize = 48 * scale,
            insufficientData = false,
        )
    }

    private companion object {
        const val TAG = "UniEatEvents"
    }
}
