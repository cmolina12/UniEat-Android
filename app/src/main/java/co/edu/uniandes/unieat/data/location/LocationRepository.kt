package co.edu.uniandes.unieat.data.location

import co.edu.uniandes.unieat.core.decision.Coordinate
import kotlinx.coroutines.flow.Flow

/** A device fix. [accuracyMeters] is the radius of uncertainty, null if the provider gave none. */
data class UserLocation(val coordinate: Coordinate, val accuracyMeters: Float?)

/** Device location for ViewModels. Callers check the runtime permission first. */
interface LocationRepository {
    /**
     * Whether location (GPS) is on in system settings: current value first, then every change,
     * including the quick-settings tile (which does not pause the Activity).
     */
    fun locationEnabled(): Flow<Boolean>

    /**
     * Fixes while collected; stops the sensor when the collector cancels.
     * [precise] = fine permission granted (GPS) vs. coarse only (network, ~km).
     * Fails with [SecurityException] if the permission was revoked.
     */
    fun locationUpdates(precise: Boolean): Flow<UserLocation>
}
