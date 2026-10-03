package co.edu.uniandes.unieat.core.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTest {
    private val bowls = Coordinate(4.6036, -74.0640)
    private val ajiaco = Coordinate(4.6028, -74.0652)

    @Test
    fun haversineMatchesKnownDistances() {
        assertEquals(0.0, haversineMeters(bowls, bowls), 1e-6)
        assertEquals(111_195.0, haversineMeters(Coordinate(0.0, 0.0), Coordinate(1.0, 0.0)), 1.0)
        assertEquals(160.0, haversineMeters(bowls, ajiaco), 1.0)
        assertEquals(haversineMeters(bowls, ajiaco), haversineMeters(ajiaco, bowls), 1e-9)
    }

    @Test
    fun walkingMinutesApplyDetourAndPace() {
        assertEquals(3, proximity(bowls, 10f, ajiaco).walkingMinutes)
        assertEquals(1, proximity(bowls, 10f, bowls).walkingMinutes)
    }

    @Test
    fun arrivalNeedsToBeWithinRadiusWithAPreciseFix() {
        val near = Coordinate(4.6036 + 0.0003, -74.0640)
        assertTrue(proximity(near, 10f, bowls).arrived)
        assertTrue(proximity(near, 50f, bowls).arrived)

        assertFalse(proximity(near, 51f, bowls).arrived)
        assertFalse(proximity(near, 1_500f, bowls).arrived)
        assertFalse(proximity(near, null, bowls).arrived)

        assertFalse(proximity(ajiaco, 5f, bowls).arrived)
    }

    @Test
    fun radiusBoundary() {
        val justInside = Coordinate(bowls.latitude + 49.0 / 111_195.0, bowls.longitude)
        val justOutside = Coordinate(bowls.latitude + 51.0 / 111_195.0, bowls.longitude)
        assertTrue(proximity(justInside, 5f, bowls).arrived)
        assertFalse(proximity(justOutside, 5f, bowls).arrived)
    }
}
