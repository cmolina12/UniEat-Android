package co.edu.uniandes.unieat.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import co.edu.uniandes.unieat.core.model.MeResponse
import co.edu.uniandes.unieat.core.model.UniEatJson
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

/** The last GET /me of the signed-in user, so the app knows its role (and tabs) when it opens offline. */
interface ProfileCache {
    suspend fun save(userId: String, profile: MeResponse)

    /** null when nothing was saved, or it belongs to another account. */
    suspend fun load(userId: String): MeResponse?

    suspend fun clear()
}

/** Two keys in "unieat_settings": whose profile it is, and the profile as JSON. */
class DataStoreProfileCache(private val store: DataStore<Preferences>) : ProfileCache {
    override suspend fun save(userId: String, profile: MeResponse) {
        store.edit {
            it[USER_ID] = userId
            it[PROFILE] = UniEatJson.encodeToString(MeResponse.serializer(), profile)
        }
    }

    override suspend fun load(userId: String): MeResponse? {
        val prefs = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.first()
        if (prefs[USER_ID] != userId) return null
        return prefs[PROFILE]?.let { runCatching { UniEatJson.decodeFromString(MeResponse.serializer(), it) }.getOrNull() }
    }

    override suspend fun clear() {
        store.edit {
            it.remove(USER_ID)
            it.remove(PROFILE)
        }
    }

    private companion object {
        val USER_ID = stringPreferencesKey("profile_user_id")
        val PROFILE = stringPreferencesKey("profile_json")
    }
}
