package co.edu.uniandes.unieat.ui.performance

import co.edu.uniandes.unieat.core.model.DailyMenu
import java.time.Instant
import kotlin.math.roundToInt

data class CongestionRow(val area: String, val priceRange: String, val menus: Int, val averageWaitMinutes: Int)

fun lineCongestion(menus: List<DailyMenu>, now: Instant): List<CongestionRow> {
    val withWait = mutableListOf<Pair<DailyMenu, Int>>()
    for (menu in menus) {
        if (menu.closedAt != null || !menu.isActive(now)) continue
        val wait = if (menu.hasWaitEvidence(now)) menu.waitMinutes else menu.waitHistoricalMinutes
        if (wait != null) withWait.add(menu to wait)
    }
    val groups = withWait.groupBy { (menu, _) -> menu.area to priceRange(menu.lowestPriceCop) }
    val rows = groups.map { (key, items) ->
        CongestionRow(
            area = key.first,
            priceRange = key.second,
            menus = items.size,
            averageWaitMinutes = items.map { it.second }.average().roundToInt(),
        )
    }
    return rows.sortedByDescending { it.averageWaitMinutes }
}

private fun priceRange(price: Int): String = when {
    price <= 15_000 -> "hasta \$15.000"
    price <= 25_000 -> "\$15.001–25.000"
    price <= 35_000 -> "\$25.001–35.000"
    else -> "más de \$35.000"
}
