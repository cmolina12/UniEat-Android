package co.edu.uniandes.unieat.ui.detail

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.edu.uniandes.unieat.DemoFixture
import co.edu.uniandes.unieat.core.decision.LocationGuidance
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.MenuDish
import co.edu.uniandes.unieat.core.model.ReportKind
import co.edu.uniandes.unieat.ui.common.SpanishPresentation
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.Sticker
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import co.edu.uniandes.unieat.ui.theme.UniEatTheme
import co.edu.uniandes.unieat.ui.theme.cop
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/** Port of iOS MenuDetailView. Stateful entry point: owns the ViewModel and passes plain state down. */
@Composable
fun MenuDetailScreen(
    menuId: String,
    onBack: () -> Unit,
    onOpenMenu: (String) -> Unit,
    viewModel: MenuDetailViewModel = viewModel(key = menuId, factory = MenuDetailViewModel.factory(menuId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val distance by viewModel.distance.collectAsStateWithLifecycle()
    val arrival by viewModel.arrival.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    // Which kind the sheet opened with; null = closed. Saveable so rotation keeps it open.
    var reportKind by rememberSaveable { mutableStateOf<ReportKind?>(null) }
    val openReport = { kind: ReportKind ->
        viewModel.onReportSheetOpened()
        reportKind = kind
    }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onLocationPermissionResult(context.locationPermission(), context.canAskLocationAgain())
    }
    // Re-check on every resume: the user may have changed the permission or GPS in settings.
    LifecycleResumeEffect(viewModel) {
        viewModel.onLocationPermissionChecked(context.locationPermission())
        onPauseOrDispose {}
    }

    MenuDetailContent(
        state = state,
        distance = distance,
        arrival = arrival,
        locationActions = LocationActions(
            onAllow = { permissionLauncher.launch(arrayOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)) },
            onNotNow = viewModel::onLocationPromptDismissed,
            onArrivalAnswered = viewModel::onArrivalAnswered,
            onReportLocation = { openReport(ReportKind.LOCATION) },
        ),
        onReport = { openReport(ReportKind.UNAVAILABLE) },
        isDemo = viewModel.isDemo,
        fixtures = viewModel.fixtures,
        currentMenuId = menuId,
        onBack = onBack,
        onRetry = viewModel::load,
        onSelect = viewModel::onSelect,
        onOpenMenu = onOpenMenu,
    )

    val content = state as? MenuDetailUiState.Content
    val kind = reportKind
    if (content != null && kind != null) {
        ReportSheet(
            menu = content.menu,
            initialKind = kind,
            submission = report,
            onSubmit = viewModel::submitReport,
            onDismiss = { reportKind = null },
        )
    }
}

@Composable
private fun MenuDetailContent(
    state: MenuDetailUiState,
    distance: DistanceStatus,
    arrival: ArrivalAnswer?,
    locationActions: LocationActions,
    onReport: () -> Unit,
    isDemo: Boolean,
    fixtures: List<DemoFixture>,
    currentMenuId: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelect: () -> Unit,
    onOpenMenu: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Palette.Ink)
            }
            Box(Modifier.weight(1f)) { BrandHeader("Detalle del plato") }
        }
        if (isDemo) DemoNotice("Datos de prueba (seed.sql) · sin conexión al servidor")

        when (state) {
            MenuDetailUiState.Loading -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                CircularProgressIndicator(color = Palette.Ink)
            }
            is MenuDetailUiState.Content -> MenuBody(
                state.menu, state.location, rememberServerNow(state.clockOffset), distance, arrival, locationActions, onSelect, onReport,
            )
            is MenuDetailUiState.Gone -> StatusMessage(
                title = state.message,
                body = "Vuelve a la lista de menús para ver opciones vigentes.",
                color = Palette.Coral,
                action = { SolidButton("Volver", onClick = onBack, color = Palette.Yellow) },
            )
            is MenuDetailUiState.Error -> StatusMessage(
                title = "No pudimos abrir el menú",
                body = state.message,
                color = Palette.Cyan,
                action = { SolidButton("Reintentar", onClick = onRetry, icon = Icons.Filled.Refresh, color = Palette.Yellow) },
            )
        }

        if (fixtures.isNotEmpty()) FixturePicker(fixtures, currentMenuId, onOpenMenu)
    }
}

/** Server-aligned clock that ticks every minute, like iOS `TimelineView(.periodic(by: 60))`. */
@Composable
private fun rememberServerNow(offset: Duration): Instant {
    val now by produceState(Instant.now() + offset, offset) {
        while (true) {
            delay(60_000)
            value = Instant.now() + offset
        }
    }
    return now
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MenuBody(
    menu: DailyMenu,
    location: LocationGuidance,
    now: Instant,
    distance: DistanceStatus,
    arrival: ArrivalAnswer?,
    locationActions: LocationActions,
    onSelect: () -> Unit,
    onReport: () -> Unit,
) {
    val active = menu.isActive(now)

    FoodArtwork(menu.establishmentName)
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(menu.establishmentName, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
            Text(menu.title, style = MaterialTheme.typography.bodyMedium)
        }
        Sticker("Desde ${menu.lowestPriceCop.cop}")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Sticker(menu.area, color = Palette.Cyan, icon = Icons.Filled.Place)
        Sticker(
            if (active) "Vigente" else "Vencido",
            color = if (active) Palette.Green else Palette.Coral,
            icon = if (active) Icons.Filled.CheckCircle else Icons.Filled.Warning,
        )
        if (menu.pendingReports > 0) Sticker("Reporte pendiente", color = Palette.Coral, icon = Icons.Filled.Info)
    }

    // Context-aware: only near the pin, with a precise fix (see core/decision/Proximity.kt).
    val arrived = (distance as? DistanceStatus.Known)?.proximity?.arrived == true
    if (arrived || arrival == ArrivalAnswer.CONFIRMED) {
        ArrivalPrompt(menu.establishmentName, arrival, locationActions.onArrivalAnswered)
    }

    WaitCard(menu, now)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Menú del día", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
        menu.items.forEach { DishCard(it) }
    }

    LocationCard(location, menu.establishmentName, now, distance, locationActions)

    SurfaceCard(Modifier.fillMaxWidth()) {
        Text("Medios de pago", style = MaterialTheme.typography.titleMedium)
        Text(
            menu.paymentMethods.ifEmpty { null }?.joinToString(" · ") ?: "Sin información declarada",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Válido hasta ${SpanishPresentation.dateAndTime(menu.validUntil)}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (active) {
        SolidButton("Elegir este menú", onClick = onSelect, icon = Icons.Filled.Check, color = Palette.Yellow)
    } else {
        Text(
            "Esta publicación venció. Vuelve a la lista de menús para ver opciones vigentes.",
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Coral.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(12.dp),
        )
    }
    SolidButton("Reportar un cambio", onClick = onReport, icon = Icons.Filled.Warning, color = Palette.Coral)
}

@Composable
private fun WaitCard(menu: DailyMenu, now: Instant) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Text("Información de espera", style = MaterialTheme.typography.titleMedium)
        val wait = menu.waitMinutes
        if (menu.hasWaitEvidence(now) && wait != null) {
            Text("~$wait minutos", fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(
                "Estimación basada en ${menu.waitSampleCount} reportes recientes. No es un tiempo garantizado.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            menu.waitNewestReportAt?.let { Text("Último reporte: ${SpanishPresentation.time(it)}", fontSize = 12.sp) }
        } else {
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .background(Palette.Cream, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text("Información insuficiente", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Hay ${menu.waitSampleCount} reporte(s) registrados. Se necesitan al menos tres y uno de " +
                        "los últimos 30 minutos para mostrar una estimación.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Puedes informar el tiempo de fila después de visitar el local.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DishCard(dish: MenuDish) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(dish.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (dish.description.isNotBlank()) {
                    Text(dish.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    when {
                        !dish.dietaryKnown -> "Dieta sin confirmar"
                        dish.dietaryTags.isEmpty() -> "Dieta declarada"
                        else -> SpanishPresentation.dietaryTags(dish.dietaryTags)
                    },
                    fontSize = 12.sp,
                )
            }
            Sticker(dish.priceCop.cop)
        }
    }
}

/** Port of iOS FoodArtwork: decorative banner until establishments have real photos. */
@Composable
private fun FoodArtwork(name: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(11.dp)) // keeps the decorative circle inside the banner
            .background(Brush.linearGradient(listOf(Palette.Yellow, Palette.Coral.copy(alpha = 0.8f)))),
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 20.dp, y = (-10).dp)
                .size(140.dp)
                .background(Color.White.copy(alpha = 0.45f), CircleShape),
        )
        Text(
            name.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            color = Palette.Ink,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .background(Palette.Paper, CircleShape)
                .padding(7.dp),
        )
    }
}

@Composable
private fun StatusMessage(title: String, body: String, color: Color, action: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
        action()
    }
}

/** Debug only: jump between the fake fixtures to check every BQ-05 case. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FixturePicker(fixtures: List<DemoFixture>, currentMenuId: String, onOpenMenu: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Menús de prueba", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fixtures.forEach { fixture ->
                val selected = fixture.menuId == currentMenuId
                Text(
                    fixture.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.Ink,
                    modifier = Modifier
                        .background(if (selected) Palette.Yellow else Palette.Paper, CircleShape)
                        .border(1.4.dp, Palette.Ink, CircleShape)
                        .clickable(enabled = !selected) { onOpenMenu(fixture.menuId) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
        }
    }
}

private fun Context.locationPermission(): LocationPermission = when {
    ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED -> LocationPermission.PRECISE
    ContextCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED -> LocationPermission.APPROXIMATE
    else -> LocationPermission.NONE
}

/** After a denial, false means Android will not show the dialog again ("no volver a preguntar"). */
private fun Context.canAskLocationAgain(): Boolean {
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, ACCESS_FINE_LOCATION) ||
        ActivityCompat.shouldShowRequestPermissionRationale(activity, ACCESS_COARSE_LOCATION)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F0E6, heightDp = 1400)
@Composable
private fun MenuDetailPreview() = UniEatTheme {
    val now = Instant.now()
    MenuDetailContent(
        state = MenuDetailUiState.Content(
            DailyMenu(
                id = "preview", title = "Tazón completo",
                validUntil = now + Duration.ofHours(5), publishedAt = now - Duration.ofMinutes(45),
                establishmentId = "e", establishmentName = "Bowls Centro Cívico", area = "Centro",
                address = "Carrera 1 #18A-70", entranceDescription = "Local junto a la esquina del bloque B",
                latitude = 4.6036, longitude = -74.064, paymentMethods = listOf("Nequi", "Tarjeta"),
                items = listOf(MenuDish("d", "Tazón de hummus", priceCop = 12_000, dietaryTags = listOf("vegan"), dietaryKnown = true)),
                lowestPriceCop = 12_000, waitSampleCount = 1,
            ),
            Duration.ZERO,
        ),
        distance = DistanceStatus.PermissionNeeded,
        arrival = null,
        locationActions = LocationActions({}, {}, {}, {}),
        onReport = {},
        isDemo = true,
        fixtures = emptyList(),
        currentMenuId = "preview",
        onBack = {},
        onRetry = {},
        onSelect = {},
        onOpenMenu = {},
    )
}
