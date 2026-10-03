package co.edu.uniandes.unieat.ui.detail

import co.edu.uniandes.unieat.core.model.DailyMenu
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The detail's validity tag at the edges of the 30-minute window. */
class ValidityTest {

    private val now = Instant.parse("2026-10-03T17:00:00Z")

    private fun endingIn(left: Duration) = DailyMenu(
        id = "m", title = "Menú", validUntil = now + left, publishedAt = now - Duration.ofHours(2),
        establishmentId = "e", establishmentName = "Local", area = "Centro", lowestPriceCop = 9_000,
    )

    @Test fun moreThanThirtyMinutesIsActive() =
        assertEquals(Validity.Active, Validity.of(endingIn(Duration.ofMinutes(31)), now))

    @Test fun exactlyThirtyMinutesIsExpiring() =
        assertEquals(Validity.Expiring(30), Validity.of(endingIn(Duration.ofMinutes(30)), now))

    @Test fun minutesAreRoundedUp() =
        assertEquals(Validity.Expiring(30), Validity.of(endingIn(Duration.ofSeconds(29 * 60 + 30)), now))

    @Test fun lastSecondsStillShowOneMinute() =
        assertEquals(Validity.Expiring(1), Validity.of(endingIn(Duration.ofSeconds(10)), now))

    @Test fun atTheDeadlineItIsExpired() =
        assertEquals(Validity.Expired, Validity.of(endingIn(Duration.ZERO), now))

    @Test fun afterTheDeadlineItIsExpired() =
        assertEquals(Validity.Expired, Validity.of(endingIn(Duration.ofMinutes(-5)), now))
}
