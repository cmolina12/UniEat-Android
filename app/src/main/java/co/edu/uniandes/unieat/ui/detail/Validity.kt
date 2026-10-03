package co.edu.uniandes.unieat.ui.detail

import co.edu.uniandes.unieat.core.model.DailyMenu
import java.time.Duration
import java.time.Instant

sealed interface Validity {
    data object Active : Validity

    data class Expiring(val minutesLeft: Long) : Validity

    data object Expired : Validity

    companion object {
        val EXPIRING_SOON: Duration = Duration.ofMinutes(30)

        fun of(menu: DailyMenu, now: Instant): Validity {
            if (!menu.isActive(now)) return Expired
            val left = Duration.between(now, menu.validUntil)
            if (left > EXPIRING_SOON) return Active
            val minutes = (left.seconds + 59) / 60
            return Expiring(minutes.coerceAtLeast(1))
        }
    }
}
