package co.edu.uniandes.unieat.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.FeedFilters
import co.edu.uniandes.unieat.core.model.MenuDish
import co.edu.uniandes.unieat.ui.common.SpanishPresentation
import co.edu.uniandes.unieat.ui.common.rememberServerNow
import co.edu.uniandes.unieat.ui.detail.locationPermission
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.Sticker
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import co.edu.uniandes.unieat.ui.theme.UniEatTheme
import co.edu.uniandes.unieat.ui.theme.cop
import java.time.Duration
import java.time.Instant

/** Feed "Hoy": menus in rank-v1 order. Stateful entry point: owns the ViewModel, passes state down. */
@Composable
fun FeedScreen(
    onOpenMenu: (String) -> Unit,
    onSessionExpired: () -> Unit,
    viewModel: FeedViewModel = viewModel(factory = FeedViewModel.factory()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val container = (context.applicationContext as UniEatApplication).container
    var showFilters by rememberSaveable { mutableStateOf(false) }

    // Context-aware: re-check the permission on every resume (it may change in settings).
    // The feed never asks for it itself; it uses the permission if the student already granted it.
    LifecycleResumeEffect(viewModel) {
        viewModel.onLocationPermissionChecked(context.locationPermission())
        onPauseOrDispose {}
    }

    // Small auth PR: an expired session sends the student back to the login.
    LaunchedEffect(state) {
        if (state is FeedUiState.SessionExpired) onSessionExpired()
    }

    FeedContent(
        state = state,
        filters = filters,
        isDemo = container.usesFakeData,
        onOpenMenu = onOpenMenu,
        onRetry = viewModel::load,
        onMenuShown = viewModel::onMenuShown,
        onOpenFilters = { showFilters = true },
    )

    if (showFilters) {
        FiltersSheet(
            current = filters,
            onApply = {
                viewModel.applyFilters(it)
                showFilters = false
            },
            onDismiss = { showFilters = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedContent(
    state: FeedUiState,
    filters: FeedFilters,
    isDemo: Boolean,
    onOpenMenu: (String) -> Unit,
    onRetry: () -> Unit,
    onMenuShown: (DailyMenu) -> Unit,
    onOpenFilters: () -> Unit,
) {
    val content = state as? FeedUiState.Content
    // Ticks every minute so menus that expire while on screen disappear, like iOS.
    val now = rememberServerNow(content?.clockOffset ?: Duration.ZERO)
    val visibleMenus = content?.menus?.filter { it.isActive(now) }.orEmpty()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BrandHeader("Hoy") }
        if (isDemo) item { DemoNotice("Datos de prueba (seed.sql) · sin conexión al servidor") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Menús vigentes",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Palette.Ink,
                    modifier = Modifier.weight(1f),
                )
                FilterButton(onOpenFilters)
            }
        }
        item {
            // Summary of the active filters, so what the list obeys is always visible.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                filters.budgetCop?.let { Sticker("Hasta ${it.cop}") }
                filters.availableMinutes?.let { Sticker("$it min", color = Palette.Paper) }
                filters.area?.let { Sticker(it, color = Palette.Cyan, icon = Icons.Filled.Place) }
                filters.diet?.let { Sticker(SpanishPresentation.diet(it), color = Palette.Green) }
                filters.paymentMethod?.let { Sticker(it, color = Palette.Paper) }
            }
        }

        when (state) {
            FeedUiState.Loading -> item {
                Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    CircularProgressIndicator(color = Palette.Ink)
                }
            }
            FeedUiState.SessionExpired -> item {
                FeedStatusCard(title = "Tu sesión terminó", body = "Inicia sesión de nuevo para ver los menús.")
            }
            is FeedUiState.Error -> item {
                FeedStatusCard(
                    title = "No pudimos cargar los menús",
                    body = state.message,
                ) { SolidButton("Reintentar", onClick = onRetry, icon = Icons.Filled.Refresh, color = Palette.Yellow) }
            }
            is FeedUiState.Content ->
                if (visibleMenus.isEmpty()) {
                    item { EmptyFeedCard() }
                } else {
                    items(visibleMenus, key = { it.id }) { menu ->
                        // A composed card in a LazyColumn is one actually on screen → one impression.
                        LaunchedEffect(menu.id) { onMenuShown(menu) }
                        MenuCard(menu, now, onClick = { onOpenMenu(menu.id) })
                    }
                }
        }
    }
}

/** "Filtros" chip next to the section title, same look as the stickers. */
@Composable
private fun FilterButton(onClick: () -> Unit) {
    Text(
        "Filtros",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Palette.Ink,
        modifier = Modifier
            .background(Palette.Yellow, CircleShape)
            .border(1.4.dp, Palette.Ink, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** One feed card: name, price, the BQ-06 wait estimate and the backend's rank explanation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MenuCard(menu: DailyMenu, now: Instant, onClick: () -> Unit) {
    SurfaceCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(menu.establishmentName, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
                    Text(menu.title, style = MaterialTheme.typography.bodyMedium)
                }
                Sticker("Desde ${menu.lowestPriceCop.cop}")
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Sticker(menu.area, color = Palette.Cyan, icon = Icons.Filled.Place)
                WaitSticker(menu, now)
                // Walking time computed by the backend from `origin`; absent without location.
                menu.travelMinutes?.let { Sticker("~$it min a pie", color = Palette.Paper) }
                if (menu.validUntil <= now + EXPIRING_SOON) {
                    Sticker("Menú por vencer", color = Palette.Coral, icon = Icons.Filled.Warning)
                }
                if (menu.pendingReports > 0) {
                    Sticker("Reporte pendiente", color = Palette.Coral, icon = Icons.Filled.Info)
                }
            }
            Text(menu.explanation, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * BQ-06: the backend computes the wait (`waitMinutes`); the app only shows it when the
 * evidence rule holds (≥3 samples, newest within 30 min). Without evidence, no number is invented.
 */
@Composable
private fun WaitSticker(menu: DailyMenu, now: Instant) {
    val wait = menu.waitMinutes
    if (wait != null && menu.hasWaitEvidence(now)) {
        Sticker("~$wait min de fila", color = Palette.Green)
    } else {
        Sticker("Fila sin datos", color = Palette.Paper)
    }
}

@Composable
internal fun EmptyFeedCard() {
    FeedStatusCard(
        title = "No hay menús que cumplan estos filtros",
        body = "Prueba otro presupuesto, zona o preferencia de dieta.",
    )
}

@Composable
internal fun FeedStatusCard(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Cyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
        action?.invoke()
    }
}

private val EXPIRING_SOON: Duration = Duration.ofMinutes(30)

@Preview(showBackground = true, backgroundColor = 0xFFF5F0E6, heightDp = 900)
@Composable
private fun FeedPreview() = UniEatTheme {
    val now = Instant.now()
    FeedContent(
        state = FeedUiState.Content(
            menus = listOf(
                DailyMenu(
                    id = "preview-1", title = "Tazón completo",
                    validUntil = now + Duration.ofHours(5), publishedAt = now - Duration.ofMinutes(45),
                    establishmentId = "e", establishmentName = "Bowls Centro Cívico", area = "Centro",
                    items = listOf(MenuDish("d", "Tazón de hummus", priceCop = 12_000)),
                    lowestPriceCop = 12_000,
                    waitMinutes = 9, waitSampleCount = 4, waitNewestReportAt = now - Duration.ofMinutes(8),
                    explanation = "Tazón de hummus por \$12.000 COP cumple presupuesto",
                ),
                DailyMenu(
                    id = "preview-2", title = "Almuerzo casero",
                    validUntil = now + Duration.ofMinutes(20), publishedAt = now - Duration.ofHours(1),
                    establishmentId = "e2", establishmentName = "Doña Elvira", area = "Norte",
                    items = listOf(MenuDish("d2", "Arroz con pollo", priceCop = 13_000)),
                    lowestPriceCop = 13_000, pendingReports = 1,
                ),
            ),
            clockOffset = Duration.ZERO,
        ),
        filters = FeedFilters(),
        isDemo = true,
        onOpenMenu = {},
        onRetry = {},
        onMenuShown = {},
        onOpenFilters = {},
    )
}
