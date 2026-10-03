package co.edu.uniandes.unieat.data.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeRefreshControllerTest {
    @Test fun `normal gravity is not a shake`() {
        assertFalse(isShake(0f, 0f, 1f, 0L, 2_000L))
    }

    @Test fun `strong acceleration after throttle is a shake`() {
        assertTrue(isShake(3f, 0f, 0f, 0L, 2_000L))
    }

    @Test fun `strong acceleration inside throttle is ignored`() {
        assertFalse(isShake(3f, 0f, 0f, 1_000L, 2_000L))
    }
}
