package co.edu.uniandes.unieat.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanSubmitReportsTest {
    @Test
    fun onlyStudentsSeeTheReportActions() {
        assertTrue(canSubmitReports("student"))
        assertFalse(canSubmitReports("restaurant"))
        assertFalse(canSubmitReports("admin"))
    }

    @Test
    fun anUnknownRoleStillLetsAStudentQueueAReportOffline() {
        assertTrue(canSubmitReports(null))
    }
}
