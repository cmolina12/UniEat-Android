package co.edu.uniandes.unieat.data.pilot

/** Same behavior as the SQL in [CachePilotDao], over a map in memory (like InMemoryReportDao). */
internal class InMemoryCachePilotDao : CachePilotDao {
    val rows = linkedMapOf<String, CachePilotSession>()

    override suspend fun upsert(session: CachePilotSession) {
        rows[session.sessionId] = session
    }

    override suspend fun open() = rows.values.filter { !it.closed }

    override suspend fun pendingUpload(limit: Int) =
        rows.values.filter { it.closed && !it.uploaded }.sortedBy { it.startedAtMillis }.take(limit)

    override suspend fun markUploaded(ids: List<String>) {
        ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(uploaded = true) } }
    }

    override suspend fun since(sinceMillis: Long) = rows.values.filter { it.startedAtMillis >= sinceMillis }

    override suspend fun deleteUploadedOlderThan(cutoffMillis: Long) {
        rows.values.removeAll { it.uploaded && it.startedAtMillis < cutoffMillis }
    }

    override suspend fun deleteNotUploaded() {
        rows.values.removeAll { !it.uploaded }
    }

    override suspend fun clear() = rows.clear()
}

internal class MemoryEnrollment(var user: String? = null) : PilotEnrollment {
    override suspend fun joinedUser() = user

    override suspend fun join(userId: String) {
        user = userId
    }

    override suspend fun leave() {
        user = null
    }
}

/** Two user ids that fall in each group, found once so the tests do not depend on a lucky guess. */
internal object PilotUsers {
    val cacheEnabled: String = (0..1000).map { "user-$it" }.first { PilotVariant.assign(it) == PilotVariant.CACHE_ENABLED }
    val onlineOnly: String = (0..1000).map { "user-$it" }.first { PilotVariant.assign(it) == PilotVariant.ONLINE_ONLY }
}
