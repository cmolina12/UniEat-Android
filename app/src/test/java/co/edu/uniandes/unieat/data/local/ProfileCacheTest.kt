package co.edu.uniandes.unieat.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import co.edu.uniandes.unieat.core.model.MeResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Real DataStore on the computer's disk, like PerformanceStorageTest. */
class ProfileCacheTest {
    private val dir: File = Files.createTempDirectory("unieat-profile").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun cache(storeScope: CoroutineScope = scope) =
        DataStoreProfileCache(PreferenceDataStoreFactory.create(scope = storeScope) { File(dir, "settings.preferences_pb") })

    private val admin = MeResponse("u-admin", "Admin", "admin")

    @Test
    fun nothingIsSavedOnTheFirstLaunch() = runBlocking {
        assertNull(cache().load("u-admin"))
    }

    @Test
    fun theSavedProfileSurvivesClosingTheApp() = runBlocking {
        cache().save("u-admin", admin)
        // Closes the first DataStore, like closing the app, and waits until it lets go of the file.
        scope.coroutineContext.job.cancelAndJoin()

        val reopened = cache(CoroutineScope(Dispatchers.IO + SupervisorJob()))
        assertEquals(admin, reopened.load("u-admin"))
    }

    @Test
    fun anotherAccountNeverGetsTheSavedProfile() = runBlocking {
        val cache = cache()
        cache.save("u-admin", admin)
        assertNull(cache.load("u-student"))
    }

    @Test
    fun clearRemovesTheProfile() = runBlocking {
        val cache = cache()
        cache.save("u-admin", admin)
        cache.clear()
        assertNull(cache.load("u-admin"))
    }
}
