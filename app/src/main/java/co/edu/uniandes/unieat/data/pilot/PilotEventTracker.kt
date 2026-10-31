package co.edu.uniandes.unieat.data.pilot

import co.edu.uniandes.unieat.data.analytics.EventKind
import co.edu.uniandes.unieat.data.analytics.EventTracker

/**
 * Samuel — Decorator pattern over the existing [EventTracker].
 *
 * BQ-13 needs to know whether a visit ended with a selection. The selection is already tracked in the
 * menu detail (`MenuDetailViewModel.onSelect` → `EventKind.SELECTION`). Instead of editing every screen
 * that tracks events, this class wraps the real tracker: it forwards every event unchanged (the analytics
 * queue keeps working exactly as before) and also tells the pilot about it through [onEvent].
 *
 * Same interface in, same interface out: AppContainer hands this object to the ViewModels as their
 * EventTracker and none of them knows the pilot exists.
 */
class PilotEventTracker(
    private val delegate: EventTracker,
    private val onEvent: (EventKind) -> Unit,
) : EventTracker {
    override fun track(kind: EventKind, publicationId: String, version: Int, screen: String, source: String?) {
        // The real event goes first: the pilot is a side observer and can never block or lose an event.
        delegate.track(kind, publicationId, version, screen, source)
        onEvent(kind)
    }
}
