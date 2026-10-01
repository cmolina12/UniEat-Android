package co.edu.uniandes.unieat.core.decision

import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.ReportKind
import java.time.Instant

// BQ-05 — Location guidance for the selected establishment.
// Pure Kotlin (no Android types) so the decision can be unit-tested on the JVM.

/** The location references a student can use to find the establishment. */
enum class LocationReference { PIN, PHOTO, ADDRESS, ENTRANCE }

data class Coordinate(val latitude: Double, val longitude: Double)

/**
 * What the location card can show for one menu version, and what it must flag.
 * Missing fields are null; [missing] lists them so the UI never shows a blank.
 */
data class LocationGuidance(
    val pin: Coordinate?,
    val photoUrl: String?,
    val address: String?,
    val entranceDescription: String?,
    /** Reports with kind = location and status = pending on this version. Not confirmed changes. */
    val pendingLocationReports: Int,
    /** publishedAt of the current version: when these references were last declared. */
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

    /** True when the card must show an uncertainty warning (missing data or open discrepancies). */
    val needsWarning: Boolean get() = missing.isNotEmpty() || hasUnresolvedDiscrepancies
}

/** Builds the BQ-05 answer from the menu detail (GET /menus/:id). */
fun locationGuidance(menu: DailyMenu): LocationGuidance = LocationGuidance(
    pin = coordinateOrNull(menu.latitude, menu.longitude),
    photoUrl = menu.photoUrl.nonBlank(),
    address = menu.address.nonBlank(),
    entranceDescription = menu.entranceDescription.nonBlank(),
    // `reports` only holds the current version's reports, so older-version reports never count.
    pendingLocationReports = menu.reports.count { it.kind == ReportKind.LOCATION && it.status == "pending" },
    updatedAt = menu.publishedAt,
)

/** A pin needs both values, in range. (0, 0) is treated as an unset default, not a real place. */
private fun coordinateOrNull(latitude: Double?, longitude: Double?): Coordinate? {
    if (latitude == null || longitude == null) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null // also rejects NaN
    if (latitude == 0.0 && longitude == 0.0) return null
    return Coordinate(latitude, longitude)
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
