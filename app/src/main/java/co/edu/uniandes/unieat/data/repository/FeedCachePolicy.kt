package co.edu.uniandes.unieat.data.repository

import co.edu.uniandes.unieat.data.pilot.PilotVariant
import java.time.Duration
import java.time.Instant

/**
 * Samuel — caching strategy of the "Hoy" feed, as a Strategy pattern.
 *
 * The feed already saves the last GET /feed per filter combination ([FileFeedCache]). What changes between
 * the two BQ-13 groups is only *how* that saved copy is used, so that decision is pulled out of
 * FeedViewModel into two interchangeable objects:
 *
 *  - [CacheThenNetwork] (cache-enabled group, and everybody outside the pilot): the copy is drawn at once
 *    while the request is on its way, and stays on screen if the request fails without signal.
 *  - [NetworkOnly] (online-only group): the feed always waits for the server and never draws the copy.
 *
 * FeedViewModel asks [of] for the policy of the current user and calls it; it has no `if (variant)`
 * branches of its own. A third policy (e.g. "copy only while offline") would be one more object.
 */
sealed interface FeedCachePolicy {
    /** The copy to draw immediately while the request runs, or null to show the spinner. */
    fun copyWhileLoading(saved: CachedFeed?): CachedFeed?

    /** The copy to keep on screen when the request fails because there is no connection, or null for the error. */
    fun copyWhenOffline(saved: CachedFeed?): CachedFeed?

    data object CacheThenNetwork : FeedCachePolicy {
        override fun copyWhileLoading(saved: CachedFeed?) = saved

        override fun copyWhenOffline(saved: CachedFeed?) = saved
    }

    data object NetworkOnly : FeedCachePolicy {
        override fun copyWhileLoading(saved: CachedFeed?): CachedFeed? = null

        override fun copyWhenOffline(saved: CachedFeed?): CachedFeed? = null
    }

    companion object {
        /**
         * Time-to-live of a saved feed. A publication lasts 24 hours at most, so a copy saved more than a day
         * ago can only hold expired menus: drawing it would show an empty list labelled "no menus match
         * your filters", which is false (the truth is "we don't know, there is no signal"). Past this age
         * the copy is not eligible, and the screen shows the honest offline message instead.
         */
        val MAX_COPY_AGE: Duration = Duration.ofHours(24)

        /** Not in the pilot (null) behaves like the cache-enabled group: the normal app never loses its cache. */
        fun of(variant: PilotVariant?): FeedCachePolicy =
            if (variant == PilotVariant.ONLINE_ONLY) NetworkOnly else CacheThenNetwork

        /** Whether [saved] is young enough to be used (or, in the pilot, counted as an available copy). */
        fun isEligible(saved: CachedFeed, now: Instant): Boolean = ageOf(saved, now) <= MAX_COPY_AGE

        /** Age of the copy on the phone clock (saving and reading use the same clock); never negative. */
        fun ageOf(saved: CachedFeed, now: Instant): Duration =
            Duration.between(saved.savedAt, now).let { if (it.isNegative) Duration.ZERO else it }
    }
}
