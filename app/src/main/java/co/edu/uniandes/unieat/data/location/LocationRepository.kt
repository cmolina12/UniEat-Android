package co.edu.uniandes.unieat.data.location

import co.edu.uniandes.unieat.core.decision.Coordinate
import kotlinx.coroutines.flow.Flow

data class UserLocation(val coordinate: Coordinate, val accuracyMeters: Float?)

interface LocationRepository {
    fun locationEnabled(): Flow<Boolean>

    fun locationUpdates(precise: Boolean): Flow<UserLocation>
}
