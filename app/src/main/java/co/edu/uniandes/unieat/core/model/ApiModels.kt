package co.edu.uniandes.unieat.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

// Request/response envelopes from docs/api-v1.md (UniEat---iOS-Back).

@Serializable
data class FeedResponse(
    @Serializable(InstantSerializer::class) val serverNow: Instant,
    @Serializable(InstantSerializer::class) val fetchedAt: Instant,
    val algorithmVersion: String = "",
    val filters: FeedFilters? = null,
    val resultCount: Int = 0,
    val menus: List<DailyMenu>,
)

@Serializable
data class MenuDetailResponse(
    @Serializable(InstantSerializer::class) val serverNow: Instant,
    @Serializable(InstantSerializer::class) val fetchedAt: Instant,
    val menu: DailyMenu,
)

@Serializable
data class MenusResponse(val menus: List<DailyMenu>)

@Serializable
data class MenuResponse(val menu: DailyMenu)

@Serializable
data class MenuBody(
    val title: String,
    @Serializable(InstantSerializer::class) val validUntil: Instant,
    val paymentMethods: List<String>,
    val dishes: List<Dish>,
    val establishmentId: String? = null,
    val establishmentName: String? = null,
    val area: String? = null,
    val address: String? = null,
    val entranceDescription: String? = null,
    /** Only for PUT /menus/:id; the version the user edited. */
    val expectedVersion: Int? = null,
) {
    @Serializable
    data class Dish(
        val name: String,
        val description: String = "",
        val category: String = "Almuerzo",
        val priceCop: Int,
        val dietaryKnown: Boolean = false,
        val dietaryTags: List<String> = emptyList(),
    )
}

@Serializable
data class CloseBody(val expectedVersion: Int? = null)

@Serializable
data class CloseResponse(
    val id: String,
    val version: Int,
    val status: MenuStatus,
    @Serializable(InstantSerializer::class) val closedAt: Instant,
)

@Serializable
data class MeResponse(
    val id: String,
    val displayName: String,
    val role: String,
    val establishments: List<EstablishmentMembership> = emptyList(),
) {
    fun toProfile() = Profile(id = id, displayName = displayName, role = role)
}

@Serializable
data class EstablishmentMembership(
    val establishmentId: String,
    val establishmentName: String,
    val area: String,
    val memberRole: String,
    val approved: Boolean,
    @Serializable(InstantSerializer::class) val approvedAt: Instant? = null,
)

@Serializable
data class ReportBody(
    val publicationId: String,
    val version: Int,
    val kind: ReportKind,
    val note: String? = null,
    val observedWaitMinutes: Int? = null,
)

@Serializable
data class ReportResponse(
    val reportId: String,
    val publicationId: String,
    val version: Int,
    val kind: ReportKind,
    val status: String,
    @Serializable(InstantSerializer::class) val createdAt: Instant,
    val message: String = "",
)

@Serializable
data class RemoteEvent(
    val eventId: String,
    val sessionId: String,
    val publicationId: String,
    val version: Int,
    val kind: String,
    @Serializable(InstantSerializer::class) val occurredAt: Instant,
    val metadata: JsonObject? = null,
)

@Serializable
data class EventBatch(
    val events: List<RemoteEvent>,
    val platform: String = "android",
)

@Serializable
data class BatchResponse(
    val accepted: Int,
    val duplicates: Int = 0,
)
