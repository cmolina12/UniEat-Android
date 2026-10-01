package co.edu.uniandes.unieat.core.decision

import co.edu.uniandes.unieat.core.decision.LocationReference.ADDRESS
import co.edu.uniandes.unieat.core.decision.LocationReference.ENTRANCE
import co.edu.uniandes.unieat.core.decision.LocationReference.PHOTO
import co.edu.uniandes.unieat.core.decision.LocationReference.PIN
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.MenuReport
import co.edu.uniandes.unieat.core.model.ReportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LocationGuidanceTest {

    private val publishedAt = Instant.parse("2026-09-29T16:34:11.216Z")

    @Test
    fun completeProfileHasEveryReferenceAndNoWarning() {
        val guidance = locationGuidance(menu(photoUrl = "https://cdn.test/bowls.jpg"))

        assertEquals(Coordinate(4.6036, -74.064), guidance.pin)
        assertEquals("Carrera 1 #18A-70", guidance.address)
        assertEquals("Local junto a la esquina del bloque B", guidance.entranceDescription)
        assertEquals("https://cdn.test/bowls.jpg", guidance.photoUrl)
        assertEquals(setOf(PIN, PHOTO, ADDRESS, ENTRANCE), guidance.available)
        assertTrue(guidance.missing.isEmpty())
        assertFalse(guidance.needsWarning)
    }

    @Test
    fun seedMenuOnlyMissesThePhoto() {
        val guidance = locationGuidance(menu())

        assertEquals(setOf(PHOTO), guidance.missing)
        assertTrue(guidance.needsWarning)
    }

    @Test
    fun missingAndBlankFieldsAreReportedAsMissing() {
        val guidance = locationGuidance(
            menu(latitude = null, longitude = null, address = "   ", entrance = "", photoUrl = " "),
        )

        assertNull(guidance.pin)
        assertNull(guidance.address)
        assertNull(guidance.entranceDescription)
        assertNull(guidance.photoUrl)
        assertEquals(setOf(PIN, PHOTO, ADDRESS, ENTRANCE), guidance.missing)
        assertTrue(guidance.available.isEmpty())
    }

    @Test
    fun textIsTrimmed() {
        val guidance = locationGuidance(menu(address = "  Calle 19 #1-21 "))
        assertEquals("Calle 19 #1-21", guidance.address)
    }

    @Test
    fun halfOrInvalidCoordinatesAreNotAPin() {
        assertNull(locationGuidance(menu(latitude = 4.6, longitude = null)).pin)
        assertNull(locationGuidance(menu(latitude = null, longitude = -74.0)).pin)
        assertNull(locationGuidance(menu(latitude = 91.0, longitude = -74.0)).pin)
        assertNull(locationGuidance(menu(latitude = 4.6, longitude = -181.0)).pin)
        assertNull(locationGuidance(menu(latitude = Double.NaN, longitude = -74.0)).pin)
        assertNull(locationGuidance(menu(latitude = 0.0, longitude = 0.0)).pin)
    }

    @Test
    fun onlyPendingLocationReportsCountAsUnresolved() {
        val guidance = locationGuidance(
            menu(
                reports = listOf(
                    report(ReportKind.LOCATION, "pending"),
                    report(ReportKind.LOCATION, "pending"),
                    report(ReportKind.LOCATION, "dismissed"),
                    report(ReportKind.LOCATION, "confirmed"),
                    report(ReportKind.PRICE, "pending"),
                    report(ReportKind.UNAVAILABLE, "pending"),
                ),
            ),
        )

        assertEquals(2, guidance.pendingLocationReports)
        assertTrue(guidance.hasUnresolvedDiscrepancies)
        assertTrue(guidance.needsWarning)
    }

    @Test
    fun pendingReportsOfOtherKindsDoNotTriggerLocationWarning() {
        val guidance = locationGuidance(
            menu(photoUrl = "https://cdn.test/p.jpg", reports = listOf(report(ReportKind.PRICE, "pending"))),
        )

        assertEquals(0, guidance.pendingLocationReports)
        assertFalse(guidance.hasUnresolvedDiscrepancies)
        assertFalse(guidance.needsWarning)
    }

    @Test
    fun updatedAtIsThePublishedAtOfTheCurrentVersion() {
        assertEquals(publishedAt, locationGuidance(menu()).updatedAt)
    }

    private fun menu(
        latitude: Double? = 4.6036,
        longitude: Double? = -74.064,
        address: String = "Carrera 1 #18A-70",
        entrance: String = "Local junto a la esquina del bloque B",
        photoUrl: String? = null,
        reports: List<MenuReport> = emptyList(),
    ) = DailyMenu(
        id = "10000000-0000-4000-8000-000000000002",
        title = "Tazón completo",
        validUntil = publishedAt.plusSeconds(3600),
        publishedAt = publishedAt,
        establishmentId = "e0000000-0000-4000-8000-000000000002",
        establishmentName = "Bowls Centro Cívico",
        area = "Centro",
        address = address,
        entranceDescription = entrance,
        latitude = latitude,
        longitude = longitude,
        photoUrl = photoUrl,
        lowestPriceCop = 12_000,
        reports = reports,
    )

    private var reportCount = 0

    private fun report(kind: ReportKind, status: String) =
        MenuReport(id = "r${reportCount++}", kind = kind, status = status, createdAt = publishedAt)
}
