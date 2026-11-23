package co.edu.uniandes.unieat.data.saved

import co.edu.uniandes.unieat.core.model.DailyMenu
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SavedMenusTest {
    private val dispatcher = StandardTestDispatcher()
    private var now = Instant.parse("2026-11-23T15:00:00Z")
    private val dao = InMemoryBookmarkedMenuDao()
    private val saved = SavedMenus(dao, clock = { now }, io = dispatcher)

    private fun menu(id: String = "m1", validFor: Duration = Duration.ofHours(2), price: Int = 12_000) = DailyMenu(
        id = id, title = "Tazón $id", validUntil = now + validFor, publishedAt = now,
        establishmentId = "e", establishmentName = "Bowls", area = "Centro", lowestPriceCop = price,
    )

    @Test
    fun toggleSavesTheMenuAndKeepsWhatTheListNeedsToShow() = runTest(dispatcher) {
        val result = saved.toggle(menu(price = 15_000))

        val row = saved.all().first().single()
        assertTrue(result)
        assertEquals("m1", row.menuId)
        assertEquals("Bowls", row.establishmentName)
        assertEquals("Centro", row.area)
        assertEquals(15_000, row.lowestPriceCop)
        assertEquals((now + Duration.ofHours(2)).toEpochMilli(), row.validUntilMillis)
        assertEquals(now.toEpochMilli(), row.savedAtMillis)
    }

    @Test
    fun toggleOnASavedMenuRemovesIt() = runTest(dispatcher) {
        saved.toggle(menu())

        val result = saved.toggle(menu())

        assertFalse(result)
        assertTrue(saved.all().first().isEmpty())
    }

    @Test
    fun isSavedFollowsTheTable() = runTest(dispatcher) {
        assertFalse(saved.isSaved("m1").first())
        saved.toggle(menu())
        assertTrue(saved.isSaved("m1").first())
        assertFalse(saved.isSaved("other").first())
        saved.remove("m1")
        assertFalse(saved.isSaved("m1").first())
    }

    @Test
    fun listIsNewestFirst() = runTest(dispatcher) {
        saved.toggle(menu("a"))
        now += Duration.ofMinutes(5)
        saved.toggle(menu("b"))
        now += Duration.ofMinutes(5)
        saved.toggle(menu("c"))

        assertEquals(listOf("c", "b", "a"), saved.all().first().map { it.menuId })
    }

    @Test
    fun twoQuickTapsLeaveTheMenuAddedThenRemoved() = runTest(dispatcher) {
        // Both calls start before either finishes: the mutex makes the second one see the first one's result.
        listOf(async { saved.toggle(menu()) }, async { saved.toggle(menu()) }).awaitAll()

        assertTrue(saved.all().first().isEmpty())
    }

    @Test
    fun keepsOnlyTheNewestMaxSavedMenus() = runTest(dispatcher) {
        repeat(SavedMenus.MAX_SAVED + 3) { i ->
            now += Duration.ofMinutes(1)
            saved.toggle(menu("m$i"))
        }

        val ids = saved.all().first().map { it.menuId }
        assertEquals(SavedMenus.MAX_SAVED, ids.size)
        assertEquals("m${SavedMenus.MAX_SAVED + 2}", ids.first())
        assertFalse("m0" in ids)
    }

    @Test
    fun purgeOldDropsOnlyMenusThatEndedMoreThanADayAgo() = runTest(dispatcher) {
        // Saved now, valid for 1 h: 26 h later it ended 25 h ago.
        saved.toggle(menu("old", validFor = Duration.ofHours(1)))
        now += Duration.ofHours(26)
        // Ended 23 h ago, and one that is still running.
        saved.toggle(menu("recent", validFor = Duration.ofHours(-23)))
        saved.toggle(menu("running", validFor = Duration.ofHours(2)))

        saved.purgeOld()

        assertEquals(setOf("recent", "running"), saved.all().first().map { it.menuId }.toSet())
    }

    @Test
    fun clearEmptiesTheList() = runTest(dispatcher) {
        saved.toggle(menu("a"))
        saved.toggle(menu("b"))

        saved.clear()

        assertTrue(saved.all().first().isEmpty())
    }
}

/** Same behaviour as the Room queries, in memory. */
internal class InMemoryBookmarkedMenuDao : BookmarkedMenuDao {
    val rows = MutableStateFlow<Map<String, BookmarkedMenu>>(emptyMap())

    override suspend fun upsert(menu: BookmarkedMenu) {
        rows.value = rows.value + (menu.menuId to menu)
    }

    override fun observeAll(): Flow<List<BookmarkedMenu>> =
        rows.map { it.values.sortedByDescending { row -> row.savedAtMillis } }

    override fun observeCount(menuId: String): Flow<Int> = rows.map { if (menuId in it) 1 else 0 }

    override suspend fun count(menuId: String): Int = if (menuId in rows.value) 1 else 0

    override suspend fun delete(menuId: String) {
        rows.value = rows.value - menuId
    }

    override suspend fun trimTo(keep: Int) {
        val kept = rows.value.values.sortedByDescending { it.savedAtMillis }.take(keep).map { it.menuId }.toSet()
        rows.value = rows.value.filterKeys { it in kept }
    }

    override suspend fun deleteValidUntilBefore(cutoffMillis: Long) {
        rows.value = rows.value.filterValues { it.validUntilMillis >= cutoffMillis }
    }

    override suspend fun clear() {
        rows.value = emptyMap()
    }
}
