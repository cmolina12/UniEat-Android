package co.edu.uniandes.unieat.ui.detail

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
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
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

/**
 * OpenStreetMap tile map (osmdroid's View wrapped in [AndroidView]) with one marker at [point].
 * It is a still preview: panning is off so the detail page keeps scrolling over it; directions
 * go through the "Abrir en Google Maps" button.
 */
@Composable
fun OsmMap(point: Coordinate, title: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Palette.Cream)) {
        // Previews cannot create a MapView; show the frame only.
        if (!LocalInspectionMode.current) MapViewHost(point, title)

        // Topmost sibling gets the touches instead of the MapView and does not consume them,
        // so the parent verticalScroll still scrolls.
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        )

        // Required by the OSM tile usage policy.
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
private fun MapViewHost(point: Coordinate, title: String) {
    val context = LocalContext.current
    val mapView = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(17.5)
        }
    }

    // MapView needs the Activity lifecycle to start/stop tile loading and release resources.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(factory = { mapView }, update = { map ->
        val geoPoint = GeoPoint(point.latitude, point.longitude)
        map.controller.setCenter(geoPoint)
        map.overlays.removeAll { it is Marker }
        map.overlays += Marker(map).apply {
            position = geoPoint
            this.title = title
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.invalidate()
    })
}

/**
 * OSM tile servers reject requests without an identifying User-Agent, so it is set to the
 * package name. Tiles are cached in app-private cache (no storage permission needed).
 */
private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    if (config.userAgentValue == context.packageName) return
    val base = File(context.cacheDir, "osmdroid")
    config.osmdroidBasePath = base
    config.osmdroidTileCache = File(base, "tiles")
    config.userAgentValue = context.packageName
}
