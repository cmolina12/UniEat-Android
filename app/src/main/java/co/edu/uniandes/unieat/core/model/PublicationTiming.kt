package co.edu.uniandes.unieat.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class PublicationTimingSnapshot(
    val periodDays: Int,
    @Serializable(InstantSerializer::class) val asOf: Instant,
    val minimumSessions: Int,
    val groups: List<PublicationTimingGroup>,
)

@Serializable
data class PublicationTimingGroup(
    val id: String,
    val name: String,
    val slots: List<PublicationTimingSlot>,
)

@Serializable
data class PublicationTimingSlot(
    val startHour: Int,
    val endHour: Int,
    val publications: Int,
    val exposedPairs: Int,
    val detailOpenRate: Double? = null,
)
