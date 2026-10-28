package co.edu.uniandes.unieat.data.local

import co.edu.uniandes.unieat.core.model.UniEatJson
import kotlinx.serialization.KSerializer
import java.time.Instant

/**
 * OfflineStore in memory for ViewModel tests. It still goes through JSON, so a model that cannot be
 * serialized fails here and not only on the phone.
 */
internal class MemoryOfflineStore(private val now: Instant = Instant.parse("2026-10-27T15:30:00Z")) : OfflineStore {
    val files = mutableMapOf<String, String>()

    override suspend fun <T> save(key: String, serializer: KSerializer<T>, value: T) {
        files[key] = UniEatJson.encodeToString(Saved.serializer(serializer), Saved(now, value))
    }

    override suspend fun <T> load(key: String, serializer: KSerializer<T>): Saved<T>? =
        files[key]?.let { UniEatJson.decodeFromString(Saved.serializer(serializer), it) }

    override suspend fun remove(key: String) {
        files.remove(key)
    }

    override suspend fun clear() = files.clear()
}
