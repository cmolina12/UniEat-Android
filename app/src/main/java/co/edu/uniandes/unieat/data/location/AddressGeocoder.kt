package co.edu.uniandes.unieat.data.location

import android.content.Context
import android.location.Geocoder
import co.edu.uniandes.unieat.core.decision.Coordinate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Turns an address into coordinates, like iOS CLGeocoder: on the phone, no API key. */
interface AddressGeocoder {
    /** null when the address is not found or the phone has no geocoder. */
    suspend fun find(address: String): Coordinate?
}

class AndroidAddressGeocoder(context: Context) : AddressGeocoder {
    private val appContext = context.applicationContext

    @Suppress("DEPRECATION") // The listener API needs API 33; the blocking call runs on IO instead.
    override suspend fun find(address: String): Coordinate? {
        val text = address.trim()
        if (text.isEmpty() || !Geocoder.isPresent()) return null
        return withContext(Dispatchers.IO) {
            // Addresses like "Calle 19 #1-21" do not carry the city, so it is added.
            val found = runCatching {
                Geocoder(appContext, Locale.forLanguageTag("es-CO")).getFromLocationName("$text, Bogotá, Colombia", 1)
            }.getOrNull()?.firstOrNull()
            found?.let { Coordinate(it.latitude, it.longitude) }
        }
    }
}
