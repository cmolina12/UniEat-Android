package co.edu.uniandes.unieat.data.analytics

import co.edu.uniandes.unieat.core.model.BatchResponse
import co.edu.uniandes.unieat.core.model.RemoteEvent
import co.edu.uniandes.unieat.data.remote.ApiError
import co.edu.uniandes.unieat.data.remote.ApiException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

/** Queue, uploader and tracker together, on the JVM with a real file in a temp folder. */
class EventPipelineTest {

    @get:Rule val tmp = TemporaryFolder()

    private var now = Instant.parse("2026-09-29T17:00:00Z")
    private fun queueFile() = tmp.root.resolve("analytics/pending-events.json")
    private fun queue(maxEvents: Int = 1_000) = EventQueue(queueFile(), maxEvents = maxEvents, clock = { now })

    private var counter = 0
    private fun event(at: Instant = now) = RemoteEvent(
        eventId = "e${counter++}", sessionId = "s", publicationId = "p", version = 1,
        kind = "detail_open", occurredAt = at,
    )

    /** Fake server: records batches; [fail] makes the next calls throw. */
    private class FakeApi : AnalyticsRepository {
        val batches = mutableListOf<List<RemoteEvent>>()
        var fail: ApiException? = null
        override suspend fun sendBatch(events: List<RemoteEvent>): BatchResponse {
            fail?.let { throw it }
            batches += events
            return BatchResponse(accepted = events.size)
        }
    }

    // --- EventQueue ---------------------------------------------------------------------------

    @Test
    fun eventsSurviveAProcessRestart() = runTest {
        queue().apply { add(event()); add(event()) }

        val reopened = queue() // new instance reads the file, like a new app process
        assertEquals(listOf("e0", "e1"), reopened.peek(10).map { it.eventId })
    }

    @Test
    fun queueIsBoundedDroppingTheOldest() = runTest {
        val q = queue(maxEvents = 3)
        repeat(5) { q.add(event()) }
        assertEquals(listOf("e2", "e3", "e4"), q.peek(10).map { it.eventId })
    }

    @Test
    fun eventsTheServerWouldRejectAsTooOldAreDiscarded() = runTest {
        val q = queue()
        q.add(event(at = now - Duration.ofDays(8)))
        q.add(event(at = now - Duration.ofDays(1)))
        assertEquals(listOf("e1"), q.peek(10).map { it.eventId })
    }

    @Test
    fun corruptFileStartsEmptyInsteadOfCrashing() = runTest {
        queueFile().apply { parentFile!!.mkdirs(); writeText("{not json") }
        assertEquals(0, queue().size())
    }

    // --- EventUploader ------------------------------------------------------------------------

    @Test
    fun uploadsInBatchesOfAtMost100AndEmptiesTheQueue() = runTest {
        val q = queue()
        repeat(250) { q.add(event()) }
        val api = FakeApi()

        assertEquals(FlushResult.DONE, EventUploader(q, api).flush())

        assertEquals(listOf(100, 100, 50), api.batches.map { it.size })
        assertEquals(0, q.size())
    }

    @Test
    fun offlineKeepsEverythingForLater() = runTest {
        val q = queue()
        repeat(3) { q.add(event()) }
        val api = FakeApi().apply { fail = ApiException.offline() }

        assertEquals(FlushResult.RETRY_LATER, EventUploader(q, api).flush())
        assertEquals(3, q.size())

        // Back online: the same event ids are sent (server dedupes by eventId).
        api.fail = null
        assertEquals(FlushResult.DONE, EventUploader(q, api).flush())
        assertEquals(listOf("e0", "e1", "e2"), api.batches.single().map { it.eventId })
    }

    @Test
    fun serverErrorsAndRateLimitsAreRetried() = runTest {
        for (code in listOf(ApiException.INTERNAL_ERROR, ApiException.RATE_LIMITED, ApiException.AUTH_REQUIRED)) {
            val q = queue().apply { add(event()) }
            val api = FakeApi().apply { fail = ApiException(ApiError(code, "x"), 500) }
            assertEquals(code, FlushResult.RETRY_LATER, EventUploader(q, api).flush())
            assertEquals(code, 1, q.size())
            q.remove(q.peek(10).mapTo(mutableSetOf()) { it.eventId })
        }
    }

    @Test
    fun malformedBatchIsDroppedSoItDoesNotBlockTheQueueForever() = runTest {
        val q = queue().apply { add(event()) }
        val api = FakeApi().apply { fail = ApiException(ApiError(ApiException.VALIDATION_ERROR, "x"), 400) }

        assertEquals(FlushResult.DONE, EventUploader(q, api).flush())
        assertEquals(0, q.size())
    }

    // --- QueuedEventTracker -------------------------------------------------------------------

    @Test
    fun trackerBuildsTheContractEventPersistsItAndSchedulesUpload() = runTest(StandardTestDispatcher()) {
        val q = EventQueue(queueFile(), clock = { now }, io = StandardTestDispatcher(testScheduler))
        var scheduled = 0
        var ids = 0
        val tracker = QueuedEventTracker(
            q, { scheduled++ }, this as TestScope, appVersion = "0.1.0",
            sessionId = "session-1", clock = { now }, newEventId = { "id-${ids++}" },
        )

        tracker.track(EventKind.ARRIVAL, "pub-1", 2, screen = "detail", source = "arrival_prompt")
        tracker.track(EventKind.DETAIL_OPEN, "pub-1", 2, screen = "detail")
        advanceUntilIdle()

        val stored = q.peek(10)
        assertEquals(listOf("id-0", "id-1"), stored.map { it.eventId })
        assertEquals(listOf("arrival", "detail_open"), stored.map { it.kind })
        assertEquals(setOf("session-1"), stored.map { it.sessionId }.toSet())
        assertEquals(now, stored[0].occurredAt)
        val metadata = stored[0].metadata!!
        assertEquals("detail", metadata["screen"]!!.jsonPrimitive.content)
        assertEquals("arrival_prompt", metadata["source"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", metadata["appVersion"]!!.jsonPrimitive.content)
        assertFalse(stored[1].metadata!!.containsKey("source"))
        assertEquals(2, scheduled)
    }
}
