package co.edu.uniandes.unieat.ui.detail

data class RecommendationNote(val criterion: String?, val reason: String) {
    companion object {
        fun from(criterion: String?, reason: String?): RecommendationNote? =
            reason?.trim()?.takeIf { it.isNotEmpty() }?.let { RecommendationNote(criterion?.trim()?.ifEmpty { null }, it) }
    }
}
