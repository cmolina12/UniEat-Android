package co.edu.uniandes.unieat.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Mis reportes (Camilo, Sprint 4). Android mirror of the iOS view: GET /me/reports/places and
 * GET /me/reports/places/:id. Only the student's own reports, the server never sends anyone else's.
 */
@Serializable
data class ReportedPlace(
    val establishmentId: String,
    val name: String,
    val area: String,
    val photoUrl: String? = null,
    val total: Int = 0,
    /** discrepancies (unavailable, price, location, not found) still in review */
    val pending: Int = 0,
    val confirmed: Int = 0,
    val dismissed: Int = 0,
    /** long line, accurate, arrival: they never stay pending */
    val observations: Int = 0,
    @Serializable(InstantSerializer::class) val lastReportAt: Instant,
) {
    val resolved: Int get() = confirmed + dismissed
}

@Serializable
data class MyReport(
    val id: String,
    val kind: String,
    val publicationId: String,
    val version: Int,
    val menuTitle: String,
    val note: String = "",
    val observedWaitMinutes: Int? = null,
    /** pending | confirmed | dismissed | observation */
    val status: String,
    /** community | admin, null while pending or for observations */
    val resolutionSource: String? = null,
    @Serializable(InstantSerializer::class) val observedAt: Instant,
    @Serializable(InstantSerializer::class) val resolvedAt: Instant? = null,
)

@Serializable
data class ReportedPlacesResponse(
    @Serializable(InstantSerializer::class) val serverNow: Instant,
    val places: List<ReportedPlace>,
)

@Serializable
data class ReportedPlaceResponse(
    @Serializable(InstantSerializer::class) val serverNow: Instant,
    val place: ReportedPlace,
    val reports: List<MyReport>,
)

/** Chips of the list, same as iOS. Observations have nothing to follow, they only show under Todos. */
enum class ReportFilter(val title: String) {
    ALL("Todos"),
    IN_REVIEW("En revisión"),
    RESOLVED("Resueltos");

    fun matches(place: ReportedPlace): Boolean = when (this) {
        ALL -> true
        IN_REVIEW -> place.pending > 0
        RESOLVED -> place.resolved > 0
    }
}
