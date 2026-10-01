package co.edu.uniandes.unieat.ui.detail

import co.edu.uniandes.unieat.data.analytics.EventKind
import co.edu.uniandes.unieat.data.analytics.EventTracker

/** Test double: remembers every tracked event. */
internal class RecordingTracker : EventTracker {
    data class Tracked(val kind: EventKind, val publicationId: String, val version: Int, val screen: String, val source: String?)

    val events = mutableListOf<Tracked>()

    override fun track(kind: EventKind, publicationId: String, version: Int, screen: String, source: String?) {
        events += Tracked(kind, publicationId, version, screen, source)
    }
}
