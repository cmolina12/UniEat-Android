package co.edu.uniandes.unieat.ui.detail

/**
 * Why "Elige por mí" picked this menu: the criterion the student chose and the backend's ranking
 * explanation. GET /menus/:id does not repeat it (its `explanation` is about validity), so the
 * recommendation screen passes it along when it opens the detail.
 */
data class RecommendationNote(val criterion: String?, val reason: String) {
    companion object {
        /** Null when the detail was not opened from a recommendation (or the reason is blank). */
        fun from(criterion: String?, reason: String?): RecommendationNote? =
            reason?.trim()?.takeIf { it.isNotEmpty() }?.let { RecommendationNote(criterion?.trim()?.ifEmpty { null }, it) }
    }
}
