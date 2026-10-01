package co.edu.uniandes.unieat.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import co.edu.uniandes.unieat.core.decision.Coordinate
import co.edu.uniandes.unieat.core.decision.LocationGuidance
import co.edu.uniandes.unieat.core.decision.LocationReference
import co.edu.uniandes.unieat.ui.common.SpanishPresentation
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.SurfaceCard
import co.edu.uniandes.unieat.ui.theme.UniEatTheme
import java.time.Duration
import java.time.Instant

/** Label shown instead of a blank when a BQ-05 reference is missing. */
private val LocationReference.missingLabel: String
    get() = when (this) {
        LocationReference.PIN -> "Ubicación no confirmada"
        LocationReference.ADDRESS -> "Dirección no publicada"
        LocationReference.ENTRANCE -> "Sin descripción de la entrada"
        LocationReference.PHOTO -> "Sin foto del local"
    }

/**
 * BQ-05 location card: renders [guidance] as-is (the decision lives in `locationGuidance()`).
 * Pending reports are shown as unverified warnings, never as changes to the published location.
 */
@Composable
fun LocationCard(
    guidance: LocationGuidance,
    establishmentName: String,
    now: Instant,
    distance: DistanceStatus,
    actions: LocationActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    SurfaceCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Place, contentDescription = null, tint = Palette.Ink)
                Text("Cómo llegar", style = MaterialTheme.typography.titleMedium)
            }

            if (guidance.hasUnresolvedDiscrepancies) DiscrepancyWarning(guidance.pendingLocationReports)

            guidance.pin?.let { pin ->
                OsmMap(
                    pin,
                    establishmentName,
                    Modifier
                        .fillMaxWidth()
                        .height(175.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.4.dp, Palette.Ink, RoundedCornerShape(10.dp)),
                )
            } ?: MissingReference(LocationReference.PIN)

            DistanceSection(distance, actions)

            guidance.photoUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = "Foto de la fachada de $establishmentName",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } ?: MissingReference(LocationReference.PHOTO)

            guidance.address?.let { ReferenceRow(Icons.Filled.Place, it) }
                ?: MissingReference(LocationReference.ADDRESS)

            guidance.entranceDescription?.let { ReferenceRow(Icons.Filled.Info, "Entrada: $it") }
                ?: MissingReference(LocationReference.ENTRANCE)

            Text(
                "¿La ubicación o la entrada no coinciden? Repórtalo",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
                color = Palette.Ink,
                modifier = Modifier.clickable(onClick = actions.onReportLocation),
            )

            Text(
                "Actualizado ${SpanishPresentation.relative(guidance.updatedAt, now)}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val pin = guidance.pin
            val address = guidance.address
            when {
                pin != null -> SolidButton(
                    "Abrir en Google Maps",
                    onClick = { openMaps(context, pin, address, establishmentName) },
                    icon = Icons.Filled.Place,
                    color = Palette.Cyan,
                )
                // Without a pin, a text search for the published address is still useful.
                address != null -> SolidButton(
                    "Buscar la dirección en Google Maps",
                    onClick = { openMaps(context, null, address, establishmentName) },
                    icon = Icons.Filled.Place,
                    color = Palette.Cyan,
                )
            }
        }
    }
}

@Composable
private fun DiscrepancyWarning(count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Coral.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .border(1.4.dp, Palette.Coral, RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                if (count == 1) "Hay 1 reporte de ubicación sin resolver: verifica al llegar."
                else "Hay $count reportes de ubicación sin resolver: verifica al llegar.",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                "Son avisos de otros estudiantes que aún no se han revisado. La ubicación publicada no ha cambiado.",
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ReferenceRow(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MissingReference(reference: LocationReference) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Cream, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = Palette.Ink.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
        Text(reference.missingLabel, fontSize = 13.sp, fontStyle = FontStyle.Italic, color = Palette.Ink.copy(alpha = 0.75f))
    }
}

/**
 * Opens Google Maps with a `geo:` URI (no API key). Falls back to any maps app, then to the
 * Google Maps website, so the button never crashes on devices without Google Maps.
 */
private fun openMaps(context: Context, pin: Coordinate?, address: String?, label: String) {
    val query = if (pin != null) "${pin.latitude},${pin.longitude}(${label})" else address.orEmpty()
    val center = if (pin != null) "${pin.latitude},${pin.longitude}" else "0,0"
    val geo = Uri.parse("geo:$center?q=${Uri.encode(query)}")
    val web = Uri.parse(
        "https://www.google.com/maps/search/?api=1&query=" +
            Uri.encode(if (pin != null) "${pin.latitude},${pin.longitude}" else address.orEmpty()),
    )
    val attempts = listOf(
        Intent(Intent.ACTION_VIEW, geo).setPackage("com.google.android.apps.maps"),
        Intent(Intent.ACTION_VIEW, geo),
        Intent(Intent.ACTION_VIEW, web),
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next option.
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F0E6)
@Composable
private fun LocationCardWarningsPreview() = UniEatTheme {
    val now = Instant.now()
    LocationCard(
        LocationGuidance(
            pin = Coordinate(4.6019, -74.0661), photoUrl = null, address = "Calle 18A #0-33",
            entranceDescription = null, pendingLocationReports = 2, updatedAt = now - Duration.ofMinutes(90),
        ),
        establishmentName = "Arepas La Esquina",
        now = now,
        distance = DistanceStatus.PermissionNeeded,
        actions = LocationActions({}, {}, {}, {}),
    )
}
