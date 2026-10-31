package co.edu.uniandes.unieat.ui.performance

import co.edu.uniandes.unieat.core.model.BatchResponse
import co.edu.uniandes.unieat.core.model.PerformanceSummary
import co.edu.uniandes.unieat.core.model.RemoteEvent
import co.edu.uniandes.unieat.data.analytics.AnalyticsRepository
import co.edu.uniandes.unieat.data.pilot.CachePilotReport
import co.edu.uniandes.unieat.data.pilot.CachePilotRepository
import co.edu.uniandes.unieat.data.pilot.CachePilotSessionDto
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Samuel — the BQ-13 card in Rendimiento: admins only, same period, never breaks the dashboard. */
@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceCachePilotTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private object Analytics : AnalyticsRepository {
        override suspend fun sendBatch(events: List<RemoteEvent>): BatchResponse = error("unused")
        override suspend fun performance(days: Int) = PerformanceSummary(days, 1, 1, 1, 1)
    }

    private class Pilot(private val fail: Boolean = false) : CachePilotRepository {
        val asked = mutableListOf<Int>()
        override suspend fun upload(sessions: List<CachePilotSessionDto>) = Unit
        override suspend fun summary(days: Int): CachePilotReport {
            asked += days
            if (fail) throw ApiException.offline()
            return CachePilotReport(days, sessions = 3)
        }
    }

    private fun vm(role: String, pilot: CachePilotRepository) =
        PerformanceViewModel(Analytics, role = role, io = dispatcher, cachePilot = pilot)

    @Test
    fun adminsGetTheCardForTheSelectedPeriod() = runTest(dispatcher) {
        val pilot = Pilot()
        val vm = vm("admin", pilot)
        advanceUntilIdle()
        vm.selectDays(28)
        advanceUntilIdle()

        assertEquals(listOf(7, 28), pilot.asked)
        assertEquals(28, (vm.state.value as PerformanceUiState.Content).cachePilot?.periodDays)
    }

    @Test
    fun restaurantsDoNotAskForIt() = runTest(dispatcher) {
        val pilot = Pilot()
        val vm = vm("restaurant", pilot)
        advanceUntilIdle()
        assertEquals(emptyList<Int>(), pilot.asked)
        assertNull((vm.state.value as PerformanceUiState.Content).cachePilot)
    }

    @Test
    fun aFailingPilotSummaryKeepsTheRestOfTheDashboard() = runTest(dispatcher) {
        val vm = vm("admin", Pilot(fail = true))
        advanceUntilIdle()

        val content = vm.state.value as PerformanceUiState.Content
        assertEquals(1, content.summary.impressions)
        assertNull(content.cachePilot)
    }
}
