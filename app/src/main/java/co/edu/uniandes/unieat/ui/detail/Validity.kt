package co.edu.uniandes.unieat.ui.detail

import co.edu.uniandes.unieat.core.model.DailyMenu
import java.time.Duration
import java.time.Instant

/** What the detail's validity tag says. Same 30-minute threshold as the feed's "Menú por vencer". */
sealed interface Validity {
    data object Active : Validity

    /** [minutesLeft] is rounded up, so the last seconds still read "1 min" until it expires. */
    data class Expiring(val minutesLeft: Long) : Validity

    data object Expired : Validity

    companion object {
        val EXPIRING_SOON: Duration = Duration.ofMinutes(30)

        /** [now] is the server-adjusted clock (see rememberServerNow), never the raw device time. */
        fun of(menu: DailyMenu, now: Instant): Validity {
            if (!menu.isActive(now)) return Expired
            val left = Duration.between(now, menu.validUntil)
            if (left > EXPIRING_SOON) return Active
            val minutes = (left.seconds + 59) / 60
            return Expiring(minutes.coerceAtLeast(1))
        }
    }
}
