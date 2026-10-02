package co.edu.uniandes.unieat.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SpanishPresentationTest {

    private val now = Instant.parse("2026-09-29T17:00:00Z")

    private fun ago(minutes: Long) = SpanishPresentation.relative(now - Duration.ofMinutes(minutes), now)

    @Test
    fun relativeTime() {
        assertEquals("hace un momento", SpanishPresentation.relative(now.plusSeconds(30), now))
        assertEquals("hace un momento", ago(0))
        assertEquals("hace 1 min", ago(1))
        assertEquals("hace 59 min", ago(59))
        assertEquals("hace 1 h", ago(60))
        assertEquals("hace 23 h", ago(24 * 60 - 1))
        assertEquals("hace 1 día", ago(24 * 60))
        assertEquals("hace 3 días", ago(3 * 24 * 60))
    }

    @Test
    fun distance() {
        assertEquals("menos de 10 m", SpanishPresentation.distance(4.0))
        assertEquals("160 m", SpanishPresentation.distance(160.4))
        assertEquals("350 m", SpanishPresentation.distance(347.0))
        assertEquals("990 m", SpanishPresentation.distance(996.0))
        assertEquals("1,2 km", SpanishPresentation.distance(1_234.0))
        assertEquals("12,0 km", SpanishPresentation.distance(12_000.0))
    }
}
