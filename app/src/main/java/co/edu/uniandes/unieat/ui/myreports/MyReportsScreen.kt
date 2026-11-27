package co.edu.uniandes.unieat.ui.myreports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.edu.uniandes.unieat.core.model.ReportFilter
import co.edu.uniandes.unieat.core.model.ReportedPlace
import co.edu.uniandes.unieat.ui.common.SpanishPresentation
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.Sticker
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import java.time.Instant

/** Mis reportes (Camilo, Sprint 4): one row per place, with how many reports are in each status. */
@Composable
fun MyReportsScreen(
    onBack: () -> Unit,
    onOpenPlace: (ReportedPlace) -> Unit,
    onSessionExpired: () -> Unit,
    vm: MyReportsViewModel = viewModel(factory = MyReportsViewModel.factory()),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.sessionExpired) { if (state.sessionExpired) onSessionExpired() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { ScreenHeader("Mis reportes", onBack) }
        item {
            Text(
                "Tus reportes agrupados por restaurante y en qué quedó cada uno.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.savedAt?.let { item { SavedNotice(it, state.loading) } }
        if (state.places.isNotEmpty()) item { Summary(state.totalReports, state.totalConfirmed) }
        item { FilterChips(state, vm::selectFilter) }
        state.error?.let { message ->
            item {
                SurfaceCard(Modifier.fillMaxWidth()) {
                    Text(message, color = Palette.Ink, fontWeight = FontWeight.Bold)
                    SolidButton("Reintentar", onClick = vm::load, icon = Icons.Filled.Refresh, color = Palette.Yellow)
                }
            }
        }
        when {
            state.loading && state.places.isEmpty() -> item { Loading("Cargando tus reportes…") }
            state.places.isEmpty() && state.error == null -> item {
                EmptyCard(
                    "Aún no tienes reportes",
                    "Abre un menú y toca \"Reportar un cambio\" para avisar de algo. Aquí verás en qué quedó.",
                )
            }
            state.visible.isEmpty() && state.places.isNotEmpty() -> item {
                EmptyCard("Nada con este filtro", "Prueba con otro filtro.")
            }
            else -> items(state.visible, key = { it.establishmentId }) { place ->
                PlaceRow(place, onOpen = { onOpenPlace(place) })
            }
        }
    }
}

@Composable
internal fun ScreenHeader(title: String, onBack: () -> Unit, subtitle: String = "Uniandes · En campus") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Palette.Ink)
        }
        Box(Modifier.weight(1f)) { BrandHeader(title, subtitle) }
    }
}

/** Rows from the phone: says when they were saved, and whether a fresh copy is on its way. */
@Composable
internal fun SavedNotice(savedAt: Instant, refreshing: Boolean) {
    val time = SpanishPresentation.dateAndTime(savedAt)
    DemoNotice(if (refreshing) "Actualizando… · mientras tanto, copia guardada el $time" else "Copia guardada el $time")
}

@Composable
private fun Summary(reports: Int, confirmed: Int) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row {
            Text("$reports reportes", Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
            Text("$confirmed confirmados", fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
        }
    }
}

/** Chips scroll sideways: a segmented control cut the labels on small phones (same reason as iOS). */
@Composable
private fun FilterChips(state: MyReportsUiState, onSelect: (ReportFilter) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReportFilter.entries.forEach { option ->
            val selected = state.filter == option
            Text(
                "${option.title} (${state.count(option)})",
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = Palette.Ink,
                modifier = Modifier
                    .background(if (selected) Palette.Yellow else Palette.Paper, RoundedCornerShape(50))
                    .border(BorderStroke(if (selected) 2.dp else 1.dp, Palette.Ink), RoundedCornerShape(50))
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceRow(place: ReportedPlace, onOpen: () -> Unit) {
    SurfaceCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(place.name, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.Ink)
            }
            Text(
                "${place.area} · último reporte ${SpanishPresentation.date(place.lastReportAt)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Sticker("${place.total} en total", color = Palette.Paper)
                if (place.pending > 0) Sticker("${place.pending} en revisión", color = Palette.Yellow)
                if (place.confirmed > 0) Sticker("${place.confirmed} confirmados", color = Palette.Green)
                if (place.dismissed > 0) Sticker("${place.dismissed} descartados", color = Palette.Coral)
            }
        }
    }
}

@Composable
internal fun Loading(text: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Palette.Ink)
        Text(text, Modifier.padding(top = 10.dp))
    }
}

@Composable
internal fun EmptyCard(title: String, body: String) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Entry in Perfil, students only (restaurants and admins can't report). */
@Composable
fun MyReportsEntry(onOpen: () -> Unit) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Mis reportes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
            Text("Mira en qué quedaron los cambios que reportaste.", fontSize = 14.sp)
            SolidButton("Ver mis reportes", onClick = onOpen, color = Palette.Yellow)
        }
    }
}
