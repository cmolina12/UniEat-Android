package co.edu.uniandes.unieat.core.decision

import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.ReportKind
import java.time.Instant

enum class LocationReference { PIN, PHOTO, ADDRESS, ENTRANCE }

data class Coordinate(val latitude: Double, val longitude: Double)

data class LocationGuidance(
    val pin: Coordinate?,
    val photoUrl: String?,
    val address: String?,
    val entranceDescription: String?,
    val pendingLocationReports: Int,
    val updatedAt: Instant,
) {
    val available: Set<LocationReference> = buildSet {
        if (pin != null) add(LocationReference.PIN)
        if (photoUrl != null) add(LocationReference.PHOTO)
        if (address != null) add(LocationReference.ADDRESS)
        if (entranceDescription != null) add(LocationReference.ENTRANCE)
    }

    val missing: Set<LocationReference> = LocationReference.entries.toSet() - available

    val hasUnresolvedDiscrepancies: Boolean get() = pendingLocationReports > 0

    val needsWarning: Boolean get() = missing.isNotEmpty() || hasUnresolvedDiscrepancies
}

fun locationGuidance(menu: DailyMenu): LocationGuidance = LocationGuidance(
    pin = coordinateOrNull(menu.latitude, menu.longitude),
    photoUrl = menu.photoUrl.nonBlank(),
    address = menu.address.nonBlank(),
    entranceDescription = menu.entranceDescription.nonBlank(),
    pendingLocationReports = menu.reports.count { it.kind == ReportKind.LOCATION && it.status == "pending" },
    updatedAt = menu.publishedAt,
)

private fun coordinateOrNull(latitude: Double?, longitude: Double?): Coordinate? {
    if (latitude == null || longitude == null) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    if (latitude == 0.0 && longitude == 0.0) return null
    return Coordinate(latitude, longitude)
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
