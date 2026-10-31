package co.edu.uniandes.unieat.data.pilot

import co.edu.uniandes.unieat.data.analytics.EventKind
import co.edu.uniandes.unieat.ui.detail.RecordingTracker
import org.junit.Assert.assertEquals
import org.junit.Test

/** Samuel — the Decorator keeps every event intact and only adds the pilot notification. */
class PilotEventTrackerTest {
    @Test
    fun everyEventStillReachesTheRealTrackerAndThePilotHearsItsKind() {
        val real = RecordingTracker()
        val heard = mutableListOf<EventKind>()
        val tracker = PilotEventTracker(real) { heard += it }

        tracker.track(EventKind.DETAIL_OPEN, "m1", 3, "detail")
        tracker.track(EventKind.SELECTION, "m1", 3, "detail", source = null)

        assertEquals(
            listOf(
                RecordingTracker.Tracked(EventKind.DETAIL_OPEN, "m1", 3, "detail", null),
                RecordingTracker.Tracked(EventKind.SELECTION, "m1", 3, "detail", null),
            ),
            real.events,
        )
        assertEquals(listOf(EventKind.DETAIL_OPEN, EventKind.SELECTION), heard)
    }
}
