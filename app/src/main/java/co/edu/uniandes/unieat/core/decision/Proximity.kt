package co.edu.uniandes.unieat.core.decision

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Walking distance from the student to the selected establishment. Pure Kotlin, unit-tested.

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Straight-line paths do not exist on campus; streets and stairs add roughly 30 %. */
private const val DETOUR_FACTOR = 1.3

/** ~4.8 km/h, an unhurried walking pace. */
private const val WALKING_METERS_PER_MINUTE = 80.0

/** "¿Ya llegaste?" shows within this radius of the pin. */
const val ARRIVAL_RADIUS_METERS = 50.0

/** Great-circle distance in meters (Haversine formula). */
fun haversineMeters(a: Coordinate, b: Coordinate): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

data class Proximity(
    val distanceMeters: Double,
    /** Approximate: straight-line distance × detour factor at walking pace, at least 1 min. */
    val walkingMinutes: Int,
    /** Inside [ARRIVAL_RADIUS_METERS] with a fix precise enough to tell. */
    val arrived: Boolean,
)

/**
 * [accuracyMeters] is the GPS fix's radius of uncertainty. An approximate fix (hundreds of meters)
 * can put the student "inside" 50 m by chance, so arrival also requires accuracy ≤ the radius.
 */
fun proximity(user: Coordinate, accuracyMeters: Float?, restaurant: Coordinate): Proximity {
    val meters = haversineMeters(user, restaurant)
    val minutes = max(1, ceil(meters * DETOUR_FACTOR / WALKING_METERS_PER_MINUTE).toInt())
    val preciseEnough = accuracyMeters != null && accuracyMeters <= ARRIVAL_RADIUS_METERS
    return Proximity(meters, minutes, arrived = meters <= ARRIVAL_RADIUS_METERS && preciseEnough)
}
