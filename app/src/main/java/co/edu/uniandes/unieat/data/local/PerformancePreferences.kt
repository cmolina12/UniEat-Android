package co.edu.uniandes.unieat.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Small settings (key-value) in their own DataStore file "unieat_settings", separate from Samuel's "unieat_session". */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "unieat_settings")

/** Rendimiento period, so it opens the way the user left it. */
interface PerformancePreferences {
    val days: Flow<Int>

    suspend fun setDays(days: Int)
}

class DataStorePerformancePreferences(private val store: DataStore<Preferences>) : PerformancePreferences {
    override val days: Flow<Int> = store.data
        // unreadable file: use the default instead of breaking the screen
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[DAYS]?.takeIf { it in VALID_DAYS } ?: DEFAULT_DAYS }

    override suspend fun setDays(days: Int) {
        require(days in VALID_DAYS)
        store.edit { it[DAYS] = days }
    }

    companion object {
        private val DAYS = intPreferencesKey("performance_days")
        val VALID_DAYS = setOf(7, 28)
        const val DEFAULT_DAYS = 7
    }
}
