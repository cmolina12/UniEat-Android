package co.edu.uniandes.unieat.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Samuel — the kind of network the phone is on right now ("wifi", "cellular", "ethernet", "other",
 * "offline" or "unknown").
 *
 * BQ-01 and BQ-13 both group their measurements by this value, so it lives in one place: if the two
 * questions labelled the same connection differently, their numbers could not be compared.
 * It reads the system state synchronously, so it can be called from any thread.
 */
object ConnectionType {
    fun current(context: Context): String {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }
}
