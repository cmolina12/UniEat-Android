package co.edu.uniandes.unieat.ui.detail

import co.edu.uniandes.unieat.data.location.LocationRepository
import co.edu.uniandes.unieat.data.location.UserLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** Test double: fixes are pushed with [emit]; [enabled] simulates the system location switch (live). */
internal class StubLocation(enabled: Boolean = true) : LocationRepository {
    val enabled = MutableStateFlow(enabled)
    val fixes = MutableSharedFlow<UserLocation>(replay = 1)
    var lastPrecise: Boolean? = null
    var collectors = 0

    override fun locationEnabled(): Flow<Boolean> = enabled

    override fun locationUpdates(precise: Boolean): Flow<UserLocation> {
        lastPrecise = precise
        collectors++
        return fixes
    }
}
