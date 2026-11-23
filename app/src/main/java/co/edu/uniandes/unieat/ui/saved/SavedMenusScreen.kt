package co.edu.uniandes.unieat.ui.saved

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.data.saved.BookmarkedMenu
import co.edu.uniandes.unieat.ui.common.SpanishPresentation
import co.edu.uniandes.unieat.ui.common.rememberServerNow
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.Sticker
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import co.edu.uniandes.unieat.ui.theme.cop
import java.time.Duration
import java.time.Instant

/** The "Guardados" view: menus the student saved, newest first. Reached from Perfil. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedMenusScreen(
    onBack: () -> Unit,
    onOpenMenu: (String) -> Unit,
    vm: SavedMenusViewModel = viewModel(factory = SavedMenusViewModel.factory()),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.purgeOld() }
    // Phone clock: only for the "posiblemente vencido" label. The detail judges validity with the server clock.
    val now = rememberServerNow(Duration.ZERO)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver", tint = Palette.Ink)
                }
                Box(Modifier.weight(1f)) { BrandHeader("Guardados") }
            }
        }
        if (state.offline) {
            item {
                DemoNotice(
                    "Sin conexión · tu lista está en el teléfono. Al abrir un menú se usa la última copia " +
                        "guardada, si la hay.",
                )
            }
        }
        when {
            state.loading -> item {
                Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    CircularProgressIndicator(color = Palette.Ink)
                }
            }
            state.menus.isEmpty() -> item {
                SurfaceCard(Modifier.fillMaxWidth()) {
                    Text("Aún no has guardado menús", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Abre un menú y toca \"Guardar para después\" para encontrarlo aquí, también sin señal.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            else -> items(state.menus, key = { it.menuId }) { menu ->
                SavedMenuCard(
                    menu = menu,
                    now = now,
                    onOpen = { onOpenMenu(menu.menuId) },
                    onRemove = { vm.remove(menu.menuId) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedMenuCard(menu: BookmarkedMenu, now: Instant, onOpen: () -> Unit, onRemove: () -> Unit) {
    val validUntil = Instant.ofEpochMilli(menu.validUntilMillis)
    SurfaceCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
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
                if (validUntil <= now) {
                    Sticker("Posiblemente vencido", color = Palette.Coral, icon = Icons.Filled.Warning)
                } else {
                    Sticker("Hasta ${SpanishPresentation.time(validUntil)}", color = Palette.Paper)
                }
            }
            Text(
                "Guardado ${SpanishPresentation.dateAndTime(Instant.ofEpochMilli(menu.savedAtMillis))}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRemove) { Text("Quitar de guardados", color = Palette.Ink) }
        }
    }
}

/** Button of the menu detail: saves the menu, or removes it when it is already saved. Works offline. */
@Composable
fun SaveMenuButton(
    menu: DailyMenu,
    vm: SavedMenusViewModel = viewModel(factory = SavedMenusViewModel.factory()),
) {
    val saved by remember(menu.id) { vm.isSaved(menu.id) }.collectAsStateWithLifecycle(initialValue = false)
    SolidButton(
        title = if (saved) "Guardado · toca para quitar" else "Guardar para después",
        onClick = { vm.toggle(menu) },
        icon = if (saved) Icons.Filled.Check else Icons.Filled.Star,
        color = if (saved) Palette.Green else Palette.Paper,
    )
}

/** Card in Perfil, for every role, that opens the list. */
@Composable
fun SavedMenusEntry(onOpen: () -> Unit) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Guardados", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
            Text(
                "Los menús que guardas se quedan en tu teléfono y puedes ver la lista sin señal.",
                fontSize = 14.sp,
            )
            SolidButton("Ver mis guardados", onClick = onOpen, color = Palette.Yellow)
        }
    }
}
