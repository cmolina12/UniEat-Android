package co.edu.uniandes.unieat.data.saved

import co.edu.uniandes.unieat.core.model.DailyMenu
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * Samuel — Sprint 4, "Guardados". The student's saved menus, kept in Room.
 *
 * Eventual connectivity: everything here is local, so saving, removing and listing work the same with or
 * without signal. Nothing is sent to the server, so there is nothing to queue.
 *
 * Multithreading: disk work runs on IO ([withContext]); the reads are `Flow`s that Room runs on its own
 * executor. A [Mutex] makes "is it saved? then remove, otherwise add" one step, so two quick taps on the
 * button cannot both read "not saved" and add twice or undo each other.
 */
class SavedMenus(
    private val dao: BookmarkedMenuDao,
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /** Newest first. */
    fun all(): Flow<List<BookmarkedMenu>> = dao.observeAll()

    fun isSaved(menuId: String): Flow<Boolean> = dao.observeCount(menuId).map { it > 0 }

    /** Saves the menu, or removes it when it was already saved. Returns true when it is saved afterwards. */
    suspend fun toggle(menu: DailyMenu): Boolean = withContext(io) {
        mutex.withLock {
            if (dao.count(menu.id) > 0) {
                dao.delete(menu.id)
                false
            } else {
                dao.upsert(BookmarkedMenu.from(menu, clock().toEpochMilli()))
                dao.trimTo(MAX_SAVED)
                true
            }
        }
    }

    suspend fun remove(menuId: String) {
        withContext(io) { mutex.withLock { dao.delete(menuId) } }
    }

    /** A menu lasts 24 h at most, so a bookmark whose menu ended days ago has nothing behind it. */
    suspend fun purgeOld() {
        withContext(io) { mutex.withLock { dao.deleteValidUntilBefore((clock() - KEEP_AFTER_VALIDITY).toEpochMilli()) } }
    }

    /** Belongs to the signed-in student, emptied on sign-out. */
    suspend fun clear() {
        withContext(io) { mutex.withLock { dao.clear() } }
    }

    companion object {
        const val MAX_SAVED = 50

        /** A menu that ended yesterday still shows as "posiblemente vencido" before it disappears. */
        val KEEP_AFTER_VALIDITY: Duration = Duration.ofHours(24)
    }
}
