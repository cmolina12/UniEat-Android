package co.edu.uniandes.unieat.data.fake

import co.edu.uniandes.unieat.DemoFixture
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.MenuDish
import co.edu.uniandes.unieat.core.model.MenuReport
import co.edu.uniandes.unieat.core.model.ReportKind
import java.time.Duration
import java.time.Instant

object SeedMenus {
    const val AJIACO = "10000000-0000-4000-8000-000000000001"
    const val BOWLS = "10000000-0000-4000-8000-000000000002"
    const val ELVIRA = "10000000-0000-4000-8000-000000000003"
    const val NUEVO_SABOR = "10000000-0000-4000-8000-000000000004"
    const val AREPAS = "10000000-0000-4000-8000-000000000005"
    const val EXPIRED = "10000000-0000-4000-8000-000000000006"

    val fixtures = listOf(
        DemoFixture(AJIACO, "Ajíaco · completo"),
        DemoFixture(BOWLS, "Bowls · seed"),
        DemoFixture(ELVIRA, "Elvira · reporte de precio"),
        DemoFixture(NUEVO_SABOR, "Nuevo Sabor · sin ubicación"),
        DemoFixture(AREPAS, "Arepas · reportes de ubicación"),
        DemoFixture(EXPIRED, "Vencido · 410"),
    )

    val seedFixtures = fixtures.take(3)

    fun all(now: Instant): List<DailyMenu> {
        val until = now + Duration.ofHours(12)
        fun ago(minutes: Long) = now - Duration.ofMinutes(minutes)

        return listOf(
            DailyMenu(
                id = AJIACO, title = "Almuerzo completo",
                validUntil = until, publishedAt = ago(35),
                establishmentId = "e0000000-0000-4000-8000-000000000001",
                establishmentName = "Ajíaco y Fríjoles", area = "Centro",
                address = "Calle 19 #1-21", entranceDescription = "Entrada junto a la plazoleta",
                latitude = 4.6028, longitude = -74.0652,
                paymentMethods = listOf("Nequi", "Efectivo"), isVerified = true,
                items = listOf(
                    dish(AJIACO, 0, "Ajíaco santafereño", 14_500, "Con arroz, aguacate y crema"),
                    dish(AJIACO, 1, "Bandeja fríjol campesino", 15_000),
                ),
                lowestPriceCop = 14_500,
                waitMinutes = 9, waitSampleCount = 4, waitNewestReportAt = ago(8),
            ),
            DailyMenu(
                id = BOWLS, title = "Tazón completo",
                validUntil = until, publishedAt = ago(45),
                establishmentId = "e0000000-0000-4000-8000-000000000002",
                establishmentName = "Bowls Centro Cívico", area = "Centro",
                address = "Carrera 1 #18A-70", entranceDescription = "Local junto a la esquina del bloque B",
                latitude = 4.6036, longitude = -74.0640,
                paymentMethods = listOf("Nequi", "Tarjeta"),
                items = listOf(
                    dish(BOWLS, 0, "Tazón de hummus", 12_000, "Vegetales, garbanzo y arroz", listOf("vegetarian", "vegan")),
                    dish(BOWLS, 1, "Tazón de pollo teriyaki", 14_000),
                ),
                lowestPriceCop = 12_000,
                waitSampleCount = 1, waitNewestReportAt = ago(10),
            ),
            DailyMenu(
                id = ELVIRA, title = "Almuerzo casero",
                validUntil = until, publishedAt = ago(55),
                establishmentId = "e0000000-0000-4000-8000-000000000003",
                establishmentName = "Doña Elvira", area = "Norte",
                address = "Calle 21 #2-15", entranceDescription = "Fachada amarilla",
                latitude = 4.6054, longitude = -74.0628,
                paymentMethods = listOf("Efectivo", "Daviplata"),
                items = listOf(
                    dish(ELVIRA, 0, "Arroz con pollo", 13_000),
                    dish(ELVIRA, 1, "Sopa de verduras", 9_000, tags = listOf("vegetarian")),
                ),
                lowestPriceCop = 9_000,
                waitMinutes = 5, waitSampleCount = 3, waitNewestReportAt = ago(15),
                reports = listOf(report(ELVIRA, 1, ReportKind.PRICE, "pending", ago(20))),
            ),
            DailyMenu(
                id = NUEVO_SABOR, title = "Corrientazo del día",
                validUntil = until, publishedAt = ago(20),
                establishmentId = "e0000000-0000-4000-8000-000000000004",
                establishmentName = "Nuevo Sabor", area = "Sur",
                address = "", entranceDescription = "",
                latitude = null, longitude = null,
                paymentMethods = listOf("Efectivo"),
                items = listOf(dish(NUEVO_SABOR, 0, "Sobrebarriga con papa", 11_000)),
                lowestPriceCop = 11_000,
            ),
            DailyMenu(
                id = AREPAS, title = "Arepas rellenas",
                validUntil = until, publishedAt = ago(90),
                establishmentId = "e0000000-0000-4000-8000-000000000005",
                establishmentName = "Arepas La Esquina", area = "Centro",
                address = "Calle 18A #0-33", entranceDescription = "Ventanilla al lado de la papelería",
                latitude = 4.6019, longitude = -74.0661,
                paymentMethods = listOf("Efectivo", "Nequi"),
                items = listOf(dish(AREPAS, 0, "Arepa de queso y pollo", 8_500, dietaryKnown = false)),
                lowestPriceCop = 8_500,
                reports = listOf(
                    report(AREPAS, 1, ReportKind.LOCATION, "pending", ago(12)),
                    report(AREPAS, 2, ReportKind.LOCATION, "pending", ago(40)),
                    report(AREPAS, 3, ReportKind.LOCATION, "dismissed", ago(80), resolvedAt = ago(60)),
                    report(AREPAS, 4, ReportKind.PRICE, "pending", ago(30)),
                ),
            ),
            DailyMenu(
                id = EXPIRED, title = "Desayuno",
                validUntil = ago(30), publishedAt = ago(300),
                establishmentId = "e0000000-0000-4000-8000-000000000001",
                establishmentName = "Ajíaco y Fríjoles", area = "Centro",
                address = "Calle 19 #1-21", entranceDescription = "Entrada junto a la plazoleta",
                latitude = 4.6028, longitude = -74.0652,
                items = listOf(dish(EXPIRED, 0, "Changua", 7_000)),
                lowestPriceCop = 7_000,
            ),
        ).map { it.copy(pendingReports = it.reports.count { r -> r.status == "pending" }) }
    }

    private fun dish(
        menuId: String,
        position: Int,
        name: String,
        priceCop: Int,
        description: String = "",
        tags: List<String> = emptyList(),
        dietaryKnown: Boolean = true,
    ) = MenuDish(
        id = "${menuId.dropLast(4)}d00$position",
        name = name,
        description = description,
        priceCop = priceCop,
        dietaryTags = tags,
        dietaryKnown = dietaryKnown,
    )

    private fun report(
        menuId: String,
        n: Int,
        kind: ReportKind,
        status: String,
        createdAt: Instant,
        resolvedAt: Instant? = null,
    ) = MenuReport(
        id = "${menuId.dropLast(4)}r00$n",
        kind = kind,
        status = status,
        createdAt = createdAt,
        resolvedAt = resolvedAt,
    )
}
