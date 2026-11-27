package co.edu.uniandes.unieat.ui.myreports

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import co.edu.uniandes.unieat.core.model.MyReport
import co.edu.uniandes.unieat.core.model.ReportKind
import co.edu.uniandes.unieat.ui.detail.reportChoices
import co.edu.uniandes.unieat.ui.theme.Palette

/** The server sends the kind as its wire name ("not_found"); find the enum by its @SerialName. */
internal fun reportKindOf(wire: String): ReportKind? {
    val descriptor = ReportKind.serializer().descriptor
    return (0 until descriptor.elementsCount)
        .firstOrNull { descriptor.getElementName(it) == wire }
        ?.let { ReportKind.entries[it] }
}

internal val MyReport.kindTitle: String
    get() = reportChoices.firstOrNull { it.first == reportKindOf(kind) }?.second ?: "Reporte"

internal val MyReport.kindIcon: ImageVector
    get() = reportChoices.firstOrNull { it.first == reportKindOf(kind) }?.third ?: Icons.Filled.Info

/** Same wording as iOS, so both apps tell the student the same thing. */
internal val MyReport.statusTitle: String
    get() = when (status) {
        "pending" -> "En revisión"
        "confirmed" -> if (resolutionSource == "community") "Confirmado por la comunidad" else "Confirmado por un administrador"
        "dismissed" -> "Descartado"
        else -> "Registrado"
    }

internal val MyReport.statusColor: Color
    get() = when (status) {
        "pending" -> Palette.Yellow
        "confirmed" -> Palette.Green
        "dismissed" -> Palette.Coral
        else -> Palette.Cyan
    }
