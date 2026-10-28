package co.edu.uniandes.unieat.data.local

import co.edu.uniandes.unieat.core.model.AdminUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Real files on the computer's disk, like PerformanceStorageTest. */
class OfflineStoreTest {
    private val dir: File = Files.createTempDirectory("unieat-offline").toFile()
    private val savedAt = Instant.parse("2026-10-27T15:30:00Z")
    private val store = FileOfflineStore(dir, Dispatchers.IO) { savedAt }
    private val users = ListSerializer(AdminUser.serializer())

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aSavedListComesBackWithTheTimeItWasSaved() = runBlocking {
        val list = listOf(AdminUser("u1", "Yo", "admin"))
        store.save("admin-users", users, list)

        val saved = store.load("admin-users", users)!!
        assertEquals(list, saved.value)
        assertEquals(savedAt, saved.savedAt)
        assertEquals(listOf("admin-users.json"), dir.list()!!.toList()) // no temporary file left behind
    }

    @Test
    fun nothingSavedIsNull() = runBlocking {
        assertNull(store.load("admin-users", users))
    }

    @Test
    fun aDamagedFileCountsAsNothingSaved() = runBlocking {
        dir.mkdirs()
        File(dir, "admin-users.json").writeText("{ no es json")
        assertNull(store.load("admin-users", users))
    }

    @Test
    fun removeDeletesOneKeyAndClearDeletesEverything() = runBlocking {
        store.save("a", users, emptyList())
        store.save("b", users, emptyList())

        store.remove("a")
        assertNull(store.load("a", users))
        assertEquals(emptyList<AdminUser>(), store.load("b", users)!!.value)

        store.clear()
        assertNull(store.load("b", users))
    }
}
