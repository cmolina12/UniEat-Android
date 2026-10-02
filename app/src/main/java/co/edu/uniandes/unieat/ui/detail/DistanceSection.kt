package co.edu.uniandes.unieat.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.edu.uniandes.unieat.core.decision.ARRIVAL_RADIUS_METERS
import co.edu.uniandes.unieat.ui.common.SpanishPresentation
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton

/** Location-related callbacks from the detail screen, grouped so they travel together. */
data class LocationActions(
    /** Shows the system permission dialog (after our explanation). */
    val onAllow: () -> Unit,
    val onNotNow: () -> Unit,
    val onArrivalAnswered: (arrived: Boolean) -> Unit,
    /** Opens the report sheet preselected on "location" (feeds the BQ-05 warning). */
    val onReportLocation: () -> Unit,
    /** A Google Maps button was tapped; [source] is "pin" or "address" (BQ-05 location_open). */
    val onOpenMaps: (source: String) -> Unit,
)

/**
 * Context-aware distance line inside the location card. Every state that prevents showing a
 * distance says why in one line, and the rest of the card stays usable.
 */
@Composable
fun DistanceSection(status: DistanceStatus, actions: LocationActions) {
    val context = LocalContext.current
    when (status) {
        DistanceStatus.Idle, DistanceStatus.NoRestaurantPin -> Unit

        DistanceStatus.PermissionNeeded -> Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.Cyan.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                .border(1.4.dp, Palette.Ink, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("¿Qué tan lejos estás?", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            Text(
                "Con tu ubicación calculamos la distancia a pie hasta el local y te preguntamos si ya " +
                    "llegaste. Solo se usa en tu teléfono mientras ves este menú; no se envía al servidor.",
                fontSize = 13.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SolidButton("Permitir ubicación", onClick = actions.onAllow, color = Palette.Yellow, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.onNotNow) { Text("Ahora no", color = Palette.Ink) }
            }
        }

        is DistanceStatus.PermissionDenied -> ContextNote(
            "Sin permiso de ubicación: no mostramos la distancia. El resto de la información sigue disponible.",
            actionLabel = if (status.permanently) "Abrir ajustes" else "Permitir ubicación",
            onAction = if (status.permanently) ({ openAppSettings(context) }) else actions.onAllow,
        )

        DistanceStatus.LocationOff -> ContextNote(
            "La ubicación del teléfono está apagada: no podemos calcular la distancia.",
            actionLabel = "Activar ubicación",
            onAction = { openLocationSettings(context) },
        )

        DistanceStatus.Searching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), color = Palette.Ink, strokeWidth = 2.dp)
            Text("Buscando tu ubicación…", fontSize = 13.sp)
        }

        DistanceStatus.Unavailable -> ContextNote("No pudimos obtener tu ubicación. La distancia no está disponible por ahora.")

        is DistanceStatus.Known -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Place, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(16.dp))
                Text(
                    "A ${SpanishPresentation.distance(status.proximity.distanceMeters)} en línea recta · " +
                        "~${status.proximity.walkingMinutes} min caminando",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
            if (status.approximate) {
                ContextNote(
                    "Ubicación aproximada: la distancia puede variar y no podemos saber si ya llegaste.",
                    actionLabel = "Usar ubicación precisa",
                    onAction = actions.onAllow,
                )
            }
        }
    }
}

/** Shown high on the page when the student is within [ARRIVAL_RADIUS_METERS] of the pin. */
@Composable
fun ArrivalPrompt(establishmentName: String, answer: ArrivalAnswer?, onAnswer: (Boolean) -> Unit) {
    when (answer) {
        null -> Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.Green, RoundedCornerShape(16.dp))
                .border(2.dp, Palette.Ink, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("¿Ya llegaste?", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
            Text(
                "Estás a menos de ${ARRIVAL_RADIUS_METERS.toInt()} m de $establishmentName.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SolidButton("Sí, llegué", onClick = { onAnswer(true) }, icon = Icons.Filled.Check, color = Palette.Yellow, modifier = Modifier.weight(1f))
                TextButton(onClick = { onAnswer(false) }) { Text("Todavía no", color = Palette.Ink) }
            }
        }
        ArrivalAnswer.CONFIRMED -> Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.Green.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Palette.Ink)
            Text("Llegada confirmada. ¡Buen provecho!", fontWeight = FontWeight.Bold)
        }
        ArrivalAnswer.DISMISSED -> Unit
    }
}

@Composable
private fun ContextNote(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Cream, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = Palette.Ink.copy(alpha = 0.6f), modifier = Modifier.size(14.dp).padding(top = 2.dp))
        Column {
            Text(text, fontSize = 13.sp, color = Palette.Ink.copy(alpha = 0.8f))
            if (actionLabel != null) {
                Text(
                    actionLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                    color = Palette.Ink,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable(onClick = onAction),
                )
            }
        }
    }
}

private fun openAppSettings(context: Context) = startSafely(
    context,
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
)

private fun openLocationSettings(context: Context) = startSafely(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

private fun startSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Some OEM builds lack these settings screens; the message already explains the situation.
    }
}
