package co.edu.uniandes.unieat.data.location

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import co.edu.uniandes.unieat.core.decision.Coordinate
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** [LocationRepository] over Google Play services' FusedLocationProviderClient. */
class FusedLocationRepository(context: Context) : LocationRepository {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    override fun locationEnabled(): Flow<Boolean> = callbackFlow {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(LocationManagerCompat.isLocationEnabled(manager))
            }
        }
        trySend(LocationManagerCompat.isLocationEnabled(manager))
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED, // system broadcasts still arrive
        )
        awaitClose { appContext.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    // The ViewModel only collects this after the permission check; a later revocation surfaces
    // as SecurityException, which the ViewModel catches.
    @SuppressLint("MissingPermission")
    override fun locationUpdates(precise: Boolean): Flow<UserLocation> = callbackFlow {
        val priority = if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, UPDATE_INTERVAL_MILLIS)
            .setMinUpdateDistanceMeters(MIN_UPDATE_METERS)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toUserLocation()) }
            }
        }

        // Show a recent cached fix right away while the first fresh one arrives.
        client.lastLocation.addOnSuccessListener { last ->
            if (last != null && System.currentTimeMillis() - last.time < MAX_CACHED_AGE_MILLIS) {
                trySend(last.toUserLocation())
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }

        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun Location.toUserLocation() = UserLocation(
        Coordinate(latitude, longitude),
        if (hasAccuracy()) accuracy else null,
    )

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 10_000L
        const val MIN_UPDATE_METERS = 5f
        const val MAX_CACHED_AGE_MILLIS = 2 * 60_000L
    }
}
