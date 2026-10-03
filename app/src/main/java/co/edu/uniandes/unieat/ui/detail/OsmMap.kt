package co.edu.uniandes.unieat.ui.detail

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import co.edu.uniandes.unieat.core.decision.Coordinate
import co.edu.uniandes.unieat.ui.theme.Palette
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import kotlin.math.roundToInt

private val UserBlue = Color(0xFF1A73E8)

private const val FIT_BOTH_MAX_METERS = 3_000.0

private const val SAME_SPOT_METERS = 15.0

private const val SHOW_ACCURACY_ABOVE_METERS = 25f

@Composable
fun OsmMap(
    point: Coordinate,
    title: String,
    modifier: Modifier = Modifier,
    user: Coordinate? = null,
    userAccuracyMeters: Float? = null,
) {
    Box(modifier.background(Palette.Cream)) {
        if (!LocalInspectionMode.current) MapViewHost(point, title, user, userAccuracyMeters)

        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        )

        MapLegend(showUser = user != null, modifier = Modifier.align(Alignment.TopStart))

        Text(
            "© OpenStreetMap contributors",
            fontSize = 10.sp,
            color = Palette.Ink,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(Color.White.copy(alpha = 0.85f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MapLegend(showUser: Boolean, modifier: Modifier) {
    Row(
        modifier
            .padding(6.dp)
            .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        LegendDot(Palette.Coral, Palette.Ink)
        Text("Local", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        if (showUser) {
            LegendDot(UserBlue, Color.White, Modifier.padding(start = 4.dp))
            Text("Tú", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        }
    }
}

@Composable
private fun LegendDot(fill: Color, stroke: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .background(fill, CircleShape)
            .border(1.5.dp, stroke, CircleShape),
    )
}

private class MapHolder(
    val map: MapView,
    val restaurant: Marker,
    val user: Marker,
    val accuracy: Polygon,
)

@Composable
private fun MapViewHost(point: Coordinate, title: String, user: Coordinate?, userAccuracyMeters: Float?) {
    val context = LocalContext.current
    val holder = remember {
        configureOsmdroid(context)
        val map = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(17.5)
        }
        MapHolder(
            map = map,
            restaurant = Marker(map).apply {
                icon = dot(context, Palette.Coral, Palette.Ink, sizeDp = 22, strokeDp = 3)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
            },
            user = Marker(map).apply {
                icon = dot(context, UserBlue, Color.White, sizeDp = 16, strokeDp = 3)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
                this.title = "Tu ubicación"
            },
            accuracy = Polygon(map).apply {
                fillPaint.color = UserBlue.copy(alpha = 0.15f).toArgb()
                outlinePaint.color = UserBlue.copy(alpha = 0.5f).toArgb()
                outlinePaint.strokeWidth = 2f
                setInfoWindow(null)
            },
        )
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, holder) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.map.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.map.onDetach()
        }
    }

    AndroidView(factory = { holder.map }, update = { map ->
        val pin = GeoPoint(point.latitude, point.longitude)
        val me = user?.let { GeoPoint(it.latitude, it.longitude) }

        holder.restaurant.position = pin
        holder.restaurant.title = title
        map.overlays.clear()
        if (me != null && userAccuracyMeters != null && userAccuracyMeters > SHOW_ACCURACY_ABOVE_METERS) {
            holder.accuracy.points = Polygon.pointsAsCircle(me, userAccuracyMeters.toDouble())
            map.overlays += holder.accuracy
        }
        map.overlays += holder.restaurant
        if (me != null) {
            holder.user.position = me
            map.overlays += holder.user
        }
        map.frame(pin, me)
        map.invalidate()
    })
}

private fun MapView.frame(pin: GeoPoint, user: GeoPoint?) {
    val apply = {
        val meters = user?.let { pin.distanceToAsDouble(it) }
        if (user == null || meters == null || meters < SAME_SPOT_METERS || meters > FIT_BOTH_MAX_METERS) {
            controller.setZoom(17.5)
            controller.setCenter(pin)
        } else {
            val border = (40 * resources.displayMetrics.density).roundToInt()
            zoomToBoundingBox(BoundingBox.fromGeoPoints(listOf(pin, user)), false, border, 18.0, null)
        }
    }
    if (isLayoutOccurred) apply() else addOnFirstLayoutListener { _, _, _, _, _ -> apply() }
}

private fun dot(context: Context, fill: Color, stroke: Color, sizeDp: Int, strokeDp: Int): Drawable {
    val density = context.resources.displayMetrics.density
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill.toArgb())
        setStroke((strokeDp * density).roundToInt(), stroke.toArgb())
        val px = (sizeDp * density).roundToInt()
        setSize(px, px)
    }
}

private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    if (config.userAgentValue == context.packageName) return
    val base = File(context.cacheDir, "osmdroid")
    config.osmdroidBasePath = base
    config.osmdroidTileCache = File(base, "tiles")
    config.userAgentValue = context.packageName
}
