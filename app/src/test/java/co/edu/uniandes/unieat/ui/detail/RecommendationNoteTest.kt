package co.edu.uniandes.unieat.ui.detail

import co.edu.uniandes.unieat.ui.navigation.Detail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecommendationNoteTest {
    @Test
    fun detailOpenedFromTheFeedHasNoNote() {
        val route = Detail("m")
        assertNull(RecommendationNote.from(route.recommendedBy, route.recommendationReason))
    }

    @Test
    fun detailOpenedFromTheRecommendationKeepsCriterionAndReason() {
        val route = Detail("m", "Más económica", "  Arroz por \$9.000 cumple tu presupuesto  ")
        assertEquals(
            RecommendationNote("Más económica", "Arroz por \$9.000 cumple tu presupuesto"),
            RecommendationNote.from(route.recommendedBy, route.recommendationReason),
        )
    }

    @Test
    fun blankReasonShowsNoCardAndBlankCriterionIsDropped() {
        assertNull(RecommendationNote.from("Mejor puntuada", "   "))
        assertEquals(RecommendationNote(null, "Vigente"), RecommendationNote.from(" ", "Vigente"))
    }
}
