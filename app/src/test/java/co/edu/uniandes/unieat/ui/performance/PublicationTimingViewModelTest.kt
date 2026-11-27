package co.edu.uniandes.unieat.ui.performance

import co.edu.uniandes.unieat.core.model.PublicationTimingSnapshot
import co.edu.uniandes.unieat.data.local.MemoryOfflineStore
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PublicationTimingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun offlineLoadsOnlyThisAccountsCopyThenReconnectsAndDropsItOnRevocation() = runTest(dispatcher) {
        val disk = MemoryOfflineStore()
        val saved = PublicationTimingSnapshot(28, Instant.parse("2026-11-25T14:00:00Z"), 5, emptyList())
        val fresh = saved.copy(asOf = saved.asOf.plusSeconds(1800))
        disk.save("publication-timing-owner", PublicationTimingSnapshot.serializer(), saved)
        disk.save("publication-timing-other", PublicationTimingSnapshot.serializer(), saved.copy(periodDays = 7))
        val online = MutableStateFlow(false)
        var calls = 0
        val model = PublicationTimingViewModel("owner", { "owner" }, {
            calls++
            if (calls > 1) throw ApiException(ApiError(ApiException.FORBIDDEN, "Fixture"), 403)
            fresh
        }, online, disk)

        advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(saved, model.state.value.snapshot)
        assertTrue(model.state.value.showingCopy)

        online.value = true
        advanceUntilIdle()
        assertEquals(fresh, model.state.value.snapshot)
        assertFalse(model.state.value.showingCopy)

        model.reload()
        advanceUntilIdle()
        assertNull(model.state.value.snapshot)
        assertNull(disk.load("publication-timing-owner", PublicationTimingSnapshot.serializer()))
        assertEquals(7, disk.load("publication-timing-other", PublicationTimingSnapshot.serializer())?.value?.periodDays)
    }
}
