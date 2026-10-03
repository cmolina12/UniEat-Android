package co.edu.uniandes.unieat.ui.feed

import co.edu.uniandes.unieat.core.model.DailyMenu
import java.time.Instant

/**
 * Strategy pattern: interchangeable criteria for "Elige por mí".
 * The backend already filters and ranks the menus; a strategy only decides
 * which of those options to highlight. No local ranking is invented.
 */
interface RecommendationStrategy {
    /** Name shown on the selection chip. */
    val label: String

    /** Picks one menu from the backend list. [index] grows with "Elegir otra opción". */
    fun pick(menus: List<DailyMenu>, index: Int, now: Instant = Instant.now()): DailyMenu?
}

/** Default: follow the backend rank-v1 order, wrapping around at the end. */
object BestRankedStrategy : RecommendationStrategy {
    override val label = "Mejor puntuada"

    override fun pick(menus: List<DailyMenu>, index: Int, now: Instant): DailyMenu? {
        if (menus.isEmpty()) return null
        return menus[index % menus.size]
    }
}

/** Cheapest option first, using the price the backend already sends. */
object CheapestStrategy : RecommendationStrategy {
    override val label = "Más económica"

    override fun pick(menus: List<DailyMenu>, index: Int, now: Instant): DailyMenu? {
        if (menus.isEmpty()) return null
        // Stable sort: menus with the same price keep the backend order
        val byPrice = menus.sortedBy { it.lowestPriceCop }
        return byPrice[index % byPrice.size]
    }
}

/** Samuel — smart-feature contribution: prefer options with a supported wait estimate, shortest first. */
object FastestWaitStrategy : RecommendationStrategy {
    override val label = "Menor fila"
    override fun pick(menus: List<DailyMenu>, index: Int, now: Instant): DailyMenu? {
        if (menus.isEmpty()) return null
        val ranked = menus.withIndex().sortedWith(
            compareBy<IndexedValue<DailyMenu>> { !it.value.hasWaitEvidence(now) }
                .thenBy { if (it.value.hasWaitEvidence(now)) it.value.waitMinutes ?: Int.MAX_VALUE else Int.MAX_VALUE }
                .thenBy { it.index }
        ).map { it.value }
        return ranked[index % ranked.size]
    }
}
