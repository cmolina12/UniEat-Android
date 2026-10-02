package co.edu.uniandes.unieat.ui.feed

import co.edu.uniandes.unieat.core.model.DailyMenu

/**
 * Strategy pattern: interchangeable criteria for "Elige por mí".
 * The backend already filters and ranks the menus; a strategy only decides
 * which of those options to highlight. No local ranking is invented.
 */
interface RecommendationStrategy {
    /** Name shown on the selection chip. */
    val label: String

    /** Picks one menu from the backend list. [index] grows with "Elegir otra opción". */
    fun pick(menus: List<DailyMenu>, index: Int): DailyMenu?
}

/** Default: follow the backend rank-v1 order, wrapping around at the end. */
object BestRankedStrategy : RecommendationStrategy {
    override val label = "Mejor puntuada"

    override fun pick(menus: List<DailyMenu>, index: Int): DailyMenu? {
        if (menus.isEmpty()) return null
        return menus[index % menus.size]
    }
}

/** Cheapest option first, using the price the backend already sends. */
object CheapestStrategy : RecommendationStrategy {
    override val label = "Más económica"

    override fun pick(menus: List<DailyMenu>, index: Int): DailyMenu? {
        if (menus.isEmpty()) return null
        // Stable sort: menus with the same price keep the backend order
        val byPrice = menus.sortedBy { it.lowestPriceCop }
        return byPrice[index % byPrice.size]
    }
}
