package co.edu.uniandes.unieat.data.pilot

import co.edu.uniandes.unieat.data.telemetry.SCOPE_ALL_DEVICES
import co.edu.uniandes.unieat.data.telemetry.SCOPE_THIS_DEVICE
import kotlinx.serialization.Serializable
import kotlin.math.ceil

/**
 * Samuel — BQ-13 answer for one cell of the comparison: one pilot group, on one connection type, with or
 * without an eligible saved copy at the start of the visit.
 *
 * "Comparable connection conditions" = the two groups are only compared inside the same
 * ([connectionType], [cacheAvailable]) pair. Splitting by [cacheAvailable] matters: a cache can only help
 * when a copy exists, so mixing visits with and without one would dilute the difference.
 */
@Serializable
data class CachePilotGroup(
    /** [PilotVariant.wireName]. */
    val variant: String,
    val connectionType: String,
    val cacheAvailable: Boolean,
    /** Ended visits in this cell. */
    val sessions: Int,
    /** Visits where some menus reached the screen (copy or network). */
    val withContent: Int,
    /** Feed-rendering time: load start → first menus on screen (nearest-rank, same as BQ-01's p95). */
    val medianFirstContentMs: Long? = null,
    val p95FirstContentMs: Long? = null,
    /** Visits that ended without tapping "Elegir este menú". */
    val withoutSelection: Int,
    /** withoutSelection / sessions, or null below [CachePilotReport.MIN_SESSIONS_FOR_SHARE] visits. */
    val withoutSelectionShare: Double? = null,
)

/**
 * The whole BQ-13 answer. The backend sends it from GET /telemetry/cache-pilot/summary with
 * [scope] = all_devices; [build] makes the same shape from this phone's rows when the server cannot answer.
 */
@Serializable
data class CachePilotReport(
    val periodDays: Int,
    val sessions: Int,
    val groups: List<CachePilotGroup> = emptyList(),
    val scope: String = SCOPE_ALL_DEVICES,
    val minSessionsForShare: Int = MIN_SESSIONS_FOR_SHARE,
) {
    companion object {
        /**
         * Below five visits a percentage is mostly noise ("1 of 2 = 50 %"), so the card shows the raw count
         * instead. Same display threshold as the iOS BQ-08 pilot. It is not a significance test.
         */
        const val MIN_SESSIONS_FOR_SHARE = 5

        /**
         * Builds the local answer. Only ended visits count: an open one cannot be "without selection" yet.
         * Rows outside the period were already filtered by the caller's query.
         */
        fun build(rows: List<CachePilotSession>, days: Int): CachePilotReport {
            val ended = rows.filter { it.closed }
            val groups = ended
                .groupBy { Triple(it.variant, it.connectionType, it.cacheAvailable) }
                .map { (key, visits) ->
                    val times = visits.mapNotNull { it.firstContentMs }.sorted()
                    val without = visits.count { !it.selected }
                    CachePilotGroup(
                        variant = key.first,
                        connectionType = key.second,
                        cacheAvailable = key.third,
                        sessions = visits.size,
                        withContent = times.size,
                        medianFirstContentMs = nearestRank(times, 0.50),
                        p95FirstContentMs = nearestRank(times, 0.95),
                        withoutSelection = without,
                        withoutSelectionShare =
                            if (visits.size >= MIN_SESSIONS_FOR_SHARE) without.toDouble() / visits.size else null,
                    )
                }
                // Same connection together, "had a copy" first, then cache_enabled before online_only.
                .sortedWith(compareBy<CachePilotGroup>({ it.connectionType }, { !it.cacheAvailable }, { it.variant }))
            return CachePilotReport(days, ended.size, groups, SCOPE_THIS_DEVICE)
        }

        /**
         * Nearest-rank percentile of an already sorted list: the value at position ceil(n·q). It always
         * returns a real measurement (no interpolation), like PostgreSQL's percentile_disc, so the local
         * fallback and the server answer mean the same thing.
         */
        fun nearestRank(sorted: List<Long>, q: Double): Long? {
            if (sorted.isEmpty()) return null
            val index = (ceil(sorted.size * q).toInt() - 1).coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
    }
}
