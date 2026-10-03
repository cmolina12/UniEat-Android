package co.edu.uniandes.unieat.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.ui.theme.ChipRow
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.cop

// Same options as the iOS FiltersView; /feed supports exactly these five parameters.

private val TIME_OPTIONS = listOf(15, 30, 45, 60)
private val DIET_OPTIONS = listOf(null to "Todas", "vegetarian" to "Vegetariana", "vegan" to "Vegana")
private val AREA_OPTIONS = listOf("Centro", "Norte", "Sur", "Fuera del campus")
private val PAYMENT_OPTIONS = listOf(null, "Nequi", "Daviplata", "Efectivo", "Tarjeta")

/** Bottom sheet with a local draft; nothing is applied until "Aplicar filtros". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersSheet(
    current: FeedFilters,
    onApply: (FeedFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    // Plain remember: FeedFilters is not Parcelable, and losing a half-edited draft on rotation is fine.
    var draft by remember { mutableStateOf(current) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.Paper) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Filtros", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)

            SectionTitle("Presupuesto")
            val budget = draft.budgetCop ?: 20_000
            Text("Hasta ${budget.cop}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = budget.toFloat(),
                onValueChange = { draft = draft.copy(budgetCop = (it / 1_000).toInt() * 1_000) },
                valueRange = 5_000f..40_000f,
                colors = SliderDefaults.colors(thumbColor = Palette.Ink, activeTrackColor = Palette.Yellow),
            )

            SectionTitle("Tiempo disponible")
            ChipRow(
                options = TIME_OPTIONS,
                isSelected = { draft.availableMinutes == it },
                label = { "$it min" },
                onSelect = { draft = draft.copy(availableMinutes = it) },
            )

            SectionTitle("Dieta")
            ChipRow(
                options = DIET_OPTIONS,
                isSelected = { draft.diet == it.first },
                label = { it.second },
                onSelect = { draft = draft.copy(diet = it.first) },
            )
            Text(
                "La dieta desconocida nunca se presenta como confirmada.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Zona")
            ChipRow(
                options = AREA_OPTIONS,
                isSelected = { draft.area == it },
                label = { it },
                onSelect = { draft = draft.copy(area = it) },
            )

            SectionTitle("Medio de pago")
            ChipRow(
                options = PAYMENT_OPTIONS,
                isSelected = { draft.paymentMethod == it },
                label = { it ?: "Todos" },
                onSelect = { draft = draft.copy(paymentMethod = it) },
            )

            SolidButton("Aplicar filtros", onClick = { onApply(draft) }, color = Palette.Yellow)
            SolidButton("Restablecer", onClick = { draft = FeedFilters() }, color = Palette.Paper)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
}

