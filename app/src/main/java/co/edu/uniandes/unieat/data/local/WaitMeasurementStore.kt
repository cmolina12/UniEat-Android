package co.edu.uniandes.unieat.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.waitMeasurementDataStore: DataStore<Preferences> by preferencesDataStore(name = "unieat_wait_measurement")

private val ARRIVED_AT_KEY = longPreferencesKey("arrived_at")

private val PENDING_MINUTES_KEY = intPreferencesKey("pending_minutes")

private val MENU_ID_KEY = stringPreferencesKey("menu_id")

private val VERSION_KEY = intPreferencesKey("version")

private val PENDING_MENU_ID_KEY = stringPreferencesKey("pending_menu_id")

private val PENDING_VERSION_KEY = intPreferencesKey("pending_version")

const val MAX_ARRIVAL_AGE_MILLIS = MAX_WAIT_MINUTES * 60_000L

suspend fun saveArrival(context: Context, millis: Long, menuId: String, version: Int) {
    context.waitMeasurementDataStore.edit { prefs ->
        val arrivedAt = prefs[ARRIVED_AT_KEY]
        val fresh = arrivedAt != null && millis - arrivedAt < MAX_ARRIVAL_AGE_MILLIS
        if (fresh && prefs[MENU_ID_KEY] == menuId && prefs[VERSION_KEY] == version) return@edit
        prefs[ARRIVED_AT_KEY] = millis
        prefs[MENU_ID_KEY] = menuId
        prefs[VERSION_KEY] = version
    }
}

suspend fun readArrival(context: Context): Long? =
    context.waitMeasurementDataStore.data.first()[ARRIVED_AT_KEY]

suspend fun hasFreshArrival(context: Context, menuId: String, now: Long): Boolean {
    val prefs = context.waitMeasurementDataStore.data.first()
    val arrivedAt = prefs[ARRIVED_AT_KEY] ?: return false
    return prefs[MENU_ID_KEY] == menuId && now - arrivedAt < MAX_ARRIVAL_AGE_MILLIS
}

suspend fun clearArrival(context: Context) {
    context.waitMeasurementDataStore.edit { prefs -> prefs.remove(ARRIVED_AT_KEY) }
}

suspend fun savePendingMinutes(context: Context, minutes: Int, menuId: String, version: Int) {
    context.waitMeasurementDataStore.edit { prefs ->
        prefs[PENDING_MINUTES_KEY] = minutes
        prefs[PENDING_MENU_ID_KEY] = menuId
        prefs[PENDING_VERSION_KEY] = version
    }
}

suspend fun readPendingMinutes(context: Context): Int? =
    context.waitMeasurementDataStore.data.first()[PENDING_MINUTES_KEY]

suspend fun readPendingMenuId(context: Context): String? =
    context.waitMeasurementDataStore.data.first()[PENDING_MENU_ID_KEY]

suspend fun readPendingVersion(context: Context): Int? =
    context.waitMeasurementDataStore.data.first()[PENDING_VERSION_KEY]

suspend fun clearPendingMinutes(context: Context) {
    context.waitMeasurementDataStore.edit { prefs ->
        prefs.remove(PENDING_MINUTES_KEY)
        prefs.remove(PENDING_MENU_ID_KEY)
        prefs.remove(PENDING_VERSION_KEY)
    }
}

suspend fun readMenuId(context: Context): String? =
    context.waitMeasurementDataStore.data.first()[MENU_ID_KEY]

suspend fun readVersion(context: Context): Int? =
    context.waitMeasurementDataStore.data.first()[VERSION_KEY]
