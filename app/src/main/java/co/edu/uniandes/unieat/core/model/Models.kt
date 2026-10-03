package co.edu.uniandes.unieat.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

// Port of UniEatCore/Models.swift. Ids are UUID strings; dates use InstantSerializer.
// Fields the iOS model lacks but api-v1 sends (status, closedAt, travel*, reports...) are optional.

@Serializable
data class MenuDish(
    val id: String,
    val name: String,
    val description: String = "",
    val category: String = "Almuerzo",
    val priceCop: Int,
    val dietaryTags: List<String> = emptyList(),
    val dietaryKnown: Boolean = false,
)

@Serializable
data class DailyMenu(
    val id: String,
    val title: String,
    val version: Int = 1,
    val currentVersion: Int? = null,
    @Serializable(InstantSerializer::class) val validUntil: Instant,
    @Serializable(InstantSerializer::class) val publishedAt: Instant,
    @Serializable(InstantSerializer::class) val closedAt: Instant? = null,
    val establishmentId: String,
    val establishmentName: String,
    val area: String,
    val address: String = "",
    val entranceDescription: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val photoUrl: String? = null,
    val paymentMethods: List<String> = emptyList(),
    val isVerified: Boolean = false,
    val items: List<MenuDish> = emptyList(),
    val lowestPriceCop: Int,
    val waitMinutes: Int? = null,
    val waitSampleCount: Int = 0,
    @Serializable(InstantSerializer::class) val waitNewestReportAt: Instant? = null,
    val pendingReports: Int = 0,
    val status: MenuStatus? = null,
    val relevanceScore: Int = 0,
    val travelMinutes: Int? = null,
    val travelEvidence: TravelEvidence? = null,
    val explanation: String = "Menú vigente",
    val reports: List<MenuReport> = emptyList(),
) {
    fun isActive(at: Instant = Instant.now()): Boolean = publishedAt <= at && validUntil > at

    /** queue-v1 freshness: >= 3 samples and newest report at most 30 minutes old. */
    fun hasWaitEvidence(at: Instant = Instant.now()): Boolean {
        val newest = waitNewestReportAt ?: return false
        if (waitSampleCount < 3 || waitMinutes == null) return false
        return newest <= at && Duration.between(newest, at) <= Duration.ofMinutes(30)
    }
}

@Serializable
enum class MenuStatus {
    @SerialName("active") ACTIVE,
    @SerialName("expiring") EXPIRING,
    @SerialName("expired") EXPIRED,
    @SerialName("closed") CLOSED,
}

@Serializable
enum class TravelEvidence {
    @SerialName("approximate") APPROXIMATE,
    @SerialName("unknown") UNKNOWN,
}

/** A report shown in menu detail (no author or note). */
@Serializable
data class MenuReport(
    val id: String,
    val kind: ReportKind,
    val status: String,
    @Serializable(InstantSerializer::class) val createdAt: Instant,
    @Serializable(InstantSerializer::class) val resolvedAt: Instant? = null,
)

@Serializable
enum class ReportKind {
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("price") PRICE,
    @SerialName("long_line") LONG_LINE,
    @SerialName("location") LOCATION,
    @SerialName("accurate") ACCURATE,
    @SerialName("arrival") ARRIVAL,
}

@Serializable
data class FeedFilters(
    val budgetCop: Int? = 20_000,
    val availableMinutes: Int? = 40,
    val diet: String? = null,
    val area: String? = "Centro",
    val paymentMethod: String? = null,
)

@Serializable
data class PerformanceSummary(
    val periodDays: Int,
    val impressions: Int,
    val detailOpens: Int,
    val selections: Int,
    val reportedArrivals: Int,
    val sampleSize: Int = 0,
    val insufficientData: Boolean = true,
    /** Clients counted: "ios", "android" or "all". Null means a backend without `?platform=` (iOS only). */
    val platform: String? = null,
)

/** BQ-05 location reports of one platform, by their current moderation status. */
@Serializable
data class LocationReportCounts(
    val pending: Int = 0,
    val confirmed: Int = 0,
    val dismissed: Int = 0,
)

/** BQ-05 signals of one platform: Maps opens, self-reported arrivals and location reports. */
@Serializable
data class LocationSignals(
    val locationOpens: Int = 0,
    val reportedArrivals: Int = 0,
    val locationReports: LocationReportCounts = LocationReportCounts(),
)

/** Location references every establishment has (or lacks) right now, independent of the period. */
@Serializable
data class LocationCoverage(
    val establishments: Int = 0,
    val withCoordinates: Int = 0,
    val withoutCoordinates: Int = 0,
    val withEntranceDescription: Int = 0,
    val withoutEntranceDescription: Int = 0,
    val withPhoto: Int = 0,
    val withoutPhoto: Int = 0,
)

/**
 * `bq05` of GET /admin/dashboard. Unlike [PerformanceSummary] it is split by platform;
 * [unknown] holds records sent without the X-UniEat-Platform header.
 */
@Serializable
data class LocationGuidanceSnapshot(
    val periodDays: Int,
    val ios: LocationSignals = LocationSignals(),
    val android: LocationSignals = LocationSignals(),
    val unknown: LocationSignals = LocationSignals(),
    val coverage: LocationCoverage = LocationCoverage(),
)

/** GET /admin/dashboard: only `bq05` is read; BQ-03, BQ-04 and engagement are ignored here. */
@Serializable
data class AdminDashboardResponse(val bq05: LocationGuidanceSnapshot? = null)

@Serializable
data class Profile(
    val id: String,
    val displayName: String,
    val role: String,
) {
    val isRestaurant: Boolean get() = role == "restaurant"
    val isAdmin: Boolean get() = role == "admin"
}

@Serializable
data class Establishment(
    val id: String,
    val ownerId: String? = null,
    val name: String,
    val area: String,
    val address: String = "",
    val entranceDescription: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val photoUrl: String? = null,
    val paymentMethods: List<String> = emptyList(),
    val isVerified: Boolean = false,
)
