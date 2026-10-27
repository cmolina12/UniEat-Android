package co.edu.uniandes.unieat.data.fake

import android.util.Log
import co.edu.uniandes.unieat.core.model.BatchResponse
import co.edu.uniandes.unieat.core.model.CompletenessGroup
import co.edu.uniandes.unieat.core.model.CompletenessGroups
import co.edu.uniandes.unieat.core.model.LocationCompletenessSnapshot
import co.edu.uniandes.unieat.core.model.LocationCoverage
import co.edu.uniandes.unieat.core.model.LocationGuidanceSnapshot
import co.edu.uniandes.unieat.core.model.LocationReportCounts
import co.edu.uniandes.unieat.core.model.LocationSignals
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import co.edu.uniandes.unieat.core.model.RemoteEvent
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository

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
            platform = "all",
        )
    }

    override suspend fun locationGuidance(days: Int): LocationGuidanceSnapshot {
        val scale = if (days >= 28) 4 else 1
        return LocationGuidanceSnapshot(
            periodDays = days,
            ios = LocationSignals(6 * scale, 3 * scale, LocationReportCounts(pending = 1, confirmed = scale)),
            android = LocationSignals(4 * scale, 2 * scale, LocationReportCounts(pending = 2, dismissed = scale)),
            coverage = LocationCoverage(
                establishments = 4, withCoordinates = 3, withoutCoordinates = 1,
                withEntranceDescription = 2, withoutEntranceDescription = 2, withPhoto = 1, withoutPhoto = 3,
            ),
        )
    }

    /** Demo BQ-10 numbers shaped like the seed: six complete places, two incomplete. */
    override suspend fun locationCompleteness(days: Int): LocationCompletenessSnapshot {
        val scale = if (days >= 28) 4 else 1
        return LocationCompletenessSnapshot(
            periodDays = days,
            groups = CompletenessGroups(
                complete = CompletenessGroup(6, 18 * scale, 9 * scale, 1 * scale, 0.1),
                incomplete = CompletenessGroup(2, 7 * scale, 2 * scale, 3 * scale, 0.6),
            ),
        )
    }

    private companion object {
        const val TAG = "UniEatEvents"
    }
}
