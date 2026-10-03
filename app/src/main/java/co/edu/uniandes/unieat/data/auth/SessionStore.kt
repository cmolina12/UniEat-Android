package co.edu.uniandes.unieat.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.Instant

private val Context.sessionDataStore by preferencesDataStore(name = "unieat_session")

interface SessionPersistence {
    suspend fun save(session: Session)
    suspend fun load(): Session?
    suspend fun clear()
}

class SessionStore(private val context: Context) : SessionPersistence {
    override suspend fun save(session: Session) = context.sessionDataStore.edit {
        it[ACCESS] = session.accessToken; it[REFRESH] = session.refreshToken
        it[EXPIRES] = session.expiresAt.toEpochMilli(); it[USER] = session.userId
    }
    override suspend fun load(): Session? {
        val p = context.sessionDataStore.data.first()
        val access=p[ACCESS] ?: return null; val refresh=p[REFRESH] ?: return null
        val expires=p[EXPIRES] ?: return null; val user=p[USER] ?: return null
        return Session(access, refresh, Instant.ofEpochMilli(expires), user)
    }
    override suspend fun clear() = context.sessionDataStore.edit { it.clear() }
    private companion object {
        val ACCESS=stringPreferencesKey("access_token"); val REFRESH=stringPreferencesKey("refresh_token")
        val EXPIRES=longPreferencesKey("expires_at"); val USER=stringPreferencesKey("user_id")
    }
}
