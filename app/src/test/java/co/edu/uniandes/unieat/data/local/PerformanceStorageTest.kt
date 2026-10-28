package co.edu.uniandes.unieat.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Real DataStore and real files on the computer's disk: no Android device needed for these two. */
class PerformanceStorageTest {
    private val dir: File = Files.createTempDirectory("unieat-storage").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun preferences(file: String = "settings.preferences_pb") =
        DataStorePerformancePreferences(PreferenceDataStoreFactory.create(scope = scope) { File(dir, file) })

    @Test
    fun theDefaultPeriodIsSevenDays() = runBlocking {
        assertEquals(7, preferences().days.first())
    }

    @Test
    fun theChosenPeriodIsWrittenToDisk() = runBlocking {
        preferences().setDays(28)
        // Closes the first DataStore, like closing the app, and waits until it lets go of the file.
        scope.coroutineContext.job.cancelAndJoin()

        val reopened = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            File(dir, "settings.preferences_pb")
        }
        assertEquals(28, DataStorePerformancePreferences(reopened).days.first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun onlyTheTwoPeriodsTheBackendSupportsAreAccepted() = runBlocking {
        preferences().setDays(30)
    }

    private val snapshot = SavedPerformance(
        role = "admin", days = 7, savedAt = Instant.parse("2026-10-27T20:00:00Z"),
        summary = PerformanceSummary(periodDays = 7, impressions = 48, detailOpens = 21, selections = 9, reportedArrivals = 5),
    )

    @Test
    fun aSavedDashboardComesBackForTheSameRoleAndPeriodOnly() = runBlocking {
        val store = FilePerformanceSnapshots(File(dir, "performance"))
        store.save(snapshot)

        assertEquals(snapshot, store.load("admin", 7))
        assertNull(store.load("admin", 28))
        assertNull(store.load("restaurant", 7))
    }

    @Test
    fun aDamagedFileIsTreatedAsNoCopy() = runBlocking {
        val store = FilePerformanceSnapshots(File(dir, "performance"))
        store.save(snapshot)
        File(dir, "performance/performance-admin-7.json").writeText("{ not json")

        assertNull(store.load("admin", 7))
    }

    @Test
    fun signingOutDeletesEverySavedDashboard() = runBlocking {
        val store = FilePerformanceSnapshots(File(dir, "performance"))
        store.save(snapshot)
        store.clear()

        assertNull(store.load("admin", 7))
    }
}
