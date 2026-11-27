package pl.poznan.offdojade.ui.components

import android.graphics.RectF
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.domain.model.AddressResult
import pl.poznan.offdojade.domain.model.StopGroup
import pl.poznan.offdojade.domain.routing.StaticStopTimetable
import pl.poznan.offdojade.ui.localization.LocalStrings
import java.io.File
import java.util.Locale

private const val SOURCE_PICKER_STOPS = "offdojade-picker-stops-source"
private const val LAYER_PICKER_STOPS = "offdojade-picker-stops-layer"

private const val SOURCE_TIMETABLE_LINE = "offdojade-timetable-line-source"
private const val LAYER_TIMETABLE_LINE = "offdojade-timetable-line-layer"
private const val SOURCE_TIMETABLE_STOPS = "offdojade-timetable-stops-source"
private const val LAYER_TIMETABLE_STOPS = "offdojade-timetable-stops-layer"

/**
 * Полноэкранный диалог выбора остановки или произвольной точки на оффлайн-карте.
 */
@Composable
fun MapLocationPickerDialog(
    title: String,
    localMapFile: File?,
    initialLat: Double,
    initialLon: Double,
    stopGroups: List<StopGroup>,
    onPickStop: (StopGroup) -> Unit,
    onPickLocation: (AddressResult) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    var centerLatLng by remember { mutableStateOf(LatLng(initialLat, initialLon)) }
    var tappedStop by remember { mutableStateOf<StopGroup?>(null) }

    val stopsById = remember(stopGroups) { stopGroups.associateBy { it.primaryStopId } }
    val latestStopsById by rememberUpdatedState(stopsById)

    val mapView = remember { MapView(context).apply { onCreate(null) } }
    DisposableEffect(lifecycleOwner, mapView) {
        var destroyed = false
        fun destroyOnce() {
            if (!destroyed) {
                destroyed = true
                mapView.onStop()
                mapView.onDestroy()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (destroyed) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> destroyOnce()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            destroyOnce()
        }
    }

    val styleJson = remember(localMapFile?.absolutePath, isDarkTheme) {
        OfflineMapStyleHelper.buildOfflineMapStyleJson(localMapFile, isDarkTheme)
    }
    val density = LocalDensity.current
    val hitRadiusPx = remember(density) { with(density) { 24.dp.toPx() } }
    var styleInitialized by remember { mutableStateOf(false) }
    var renderedStopGroups by remember { mutableStateOf<List<StopGroup>?>(null) }
    val latestStopGroups by rememberUpdatedState(stopGroups)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
                update = { mv ->
                    mv.getMapAsync { map ->
                        fun syncStopsSource(style: Style, groups: List<StopGroup>) {
                            if (renderedStopGroups === groups && style.getSource(SOURCE_PICKER_STOPS) != null) return
                            renderedStopGroups = groups
                            val features = groups.map { stop ->
                                Feature.fromGeometry(Point.fromLngLat(stop.longitude, stop.latitude)).apply {
                                    addStringProperty("id", stop.primaryStopId)
                                    addStringProperty("name", stop.displayName)
                                }
                            }
                            val collection = FeatureCollection.fromFeatures(features)
                            val existing = style.getSourceAs<GeoJsonSource>(SOURCE_PICKER_STOPS)
                            if (existing != null) {
                                existing.setGeoJson(collection)
                            } else {
                                style.addSource(GeoJsonSource(SOURCE_PICKER_STOPS, collection))
                                style.addLayer(
                                    CircleLayer(LAYER_PICKER_STOPS, SOURCE_PICKER_STOPS)
                                        .withProperties(
                                            PropertyFactory.circleColor("#3FA372"),
                                            PropertyFactory.circleRadius(6.0f),
                                            PropertyFactory.circleStrokeWidth(2.0f),
                                            PropertyFactory.circleStrokeColor(if (isDarkTheme) "#FFFFFF" else "#0F172A")
                                        ).apply { minZoom = 13.0f }
                                )
                            }
                        }

                        if (!styleInitialized) {
                            styleInitialized = true
                            map.cameraPosition = CameraPosition.Builder()
                                .target(LatLng(initialLat, initialLon))
                                .zoom(14.0)
                                .build()

                            map.addOnCameraIdleListener {
                                map.cameraPosition.target?.let { target ->
                                    centerLatLng = target
                                }
                            }

                            map.addOnMapClickListener { latLng ->
                                val screenPt = map.projection.toScreenLocation(latLng)
                                val hitBox = RectF(
                                    screenPt.x - hitRadiusPx,
                                    screenPt.y - hitRadiusPx,
                                    screenPt.x + hitRadiusPx,
                                    screenPt.y + hitRadiusPx
                                )
                                val hit = map.queryRenderedFeatures(hitBox, LAYER_PICKER_STOPS).firstOrNull()
                                val stopId = hit?.getStringProperty("id")
                                val found = if (stopId != null) latestStopsById[stopId] else null
                                tappedStop = found
                                found != null
                            }

                            map.setStyle(Style.Builder().fromJson(styleJson)) { loadedStyle ->
                                OfflineMapStyleHelper.addPoznanLandmarksLayer(loadedStyle, isDarkTheme, density.density)
                                syncStopsSource(loadedStyle, latestStopGroups)
                            }
                        } else {
                            val loadedStyle = map.style
                            if (loadedStyle != null && loadedStyle.isFullyLoaded) {
                                syncStopsSource(loadedStyle, stopGroups)
                            }
                        }
                    }
                }
            )

            // Центральный маркер выбора точки (кончик пина строго по центру карты)
            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .offset(y = (-20).dp)
                        .size(40.dp)
                )
            }

            // Верхняя панель с кнопкой Назад и заголовком
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Нижняя карточка подтверждения выбранной остановки или точки
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(14.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val stop = tappedStop
                    if (stop != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.DirectionsBus,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stop.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${s.zonePrefix} ${stop.zones.sorted().joinToString("/").ifEmpty { "A" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Button(
                            onClick = { onPickStop(stop) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stop.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        val coordsText = String.format(
                            Locale.US,
                            "%.5f, %.5f",
                            centerLatLng.latitude,
                            centerLatLng.longitude
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = s.pickThisLocation,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = coordsText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Button(
                            onClick = {
                                onPickLocation(
                                    AddressResult(
                                        id = "map_${centerLatLng.latitude}_${centerLatLng.longitude}",
                                        displayName = coordsText,
                                        subtitle = "Poznań",
                                        latitude = centerLatLng.latitude,
                                        longitude = centerLatLng.longitude
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s.pickThisLocation)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Интерактивная оффлайн-карта остановок выбранной линии для экрана статичных расписаний.
 * Нажатие на любую остановку сразу открывает её расписание (кроме конечной без отправлений).
 */
@Composable
fun TimetableStopsMapView(
    stops: List<StaticStopTimetable>,
    lineColorHex: String,
    localMapFile: File?,
    onSelectStop: (StaticStopTimetable) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    var selectedStopBanner by remember(stops) { mutableStateOf<StaticStopTimetable?>(null) }
    val stopsById = remember(stops) { stops.associateBy { it.stopId } }
    val latestStopsById by rememberUpdatedState(stopsById)

    val mapView = remember { MapView(context).apply { onCreate(null) } }
    DisposableEffect(lifecycleOwner, mapView) {
        var destroyed = false
        fun destroyOnce() {
            if (!destroyed) {
                destroyed = true
                mapView.onStop()
                mapView.onDestroy()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (destroyed) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> destroyOnce()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            destroyOnce()
        }
    }

    val styleJson = remember(localMapFile?.absolutePath, isDarkTheme) {
        OfflineMapStyleHelper.buildOfflineMapStyleJson(localMapFile, isDarkTheme)
    }
    val density = LocalDensity.current
    val hitRadiusPx = remember(density) { with(density) { 24.dp.toPx() } }
    var appliedStyleJson by remember { mutableStateOf<String?>(null) }
    var clickListenerAttached by remember { mutableStateOf(false) }
    var lastFittedStops by remember { mutableStateOf<List<StaticStopTimetable>?>(null) }

    val validStops = remember(stops) {
        stops.filter { it.latitude != 0.0 && it.longitude != 0.0 }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                mv.getMapAsync { map ->
                    if (!clickListenerAttached) {
                        clickListenerAttached = true
                        map.addOnMapClickListener { latLng ->
                            val screenPt = map.projection.toScreenLocation(latLng)
                            val hitBox = RectF(
                                screenPt.x - hitRadiusPx,
                                screenPt.y - hitRadiusPx,
                                screenPt.x + hitRadiusPx,
                                screenPt.y + hitRadiusPx
                            )
                            val hit = map.queryRenderedFeatures(hitBox, LAYER_TIMETABLE_STOPS).firstOrNull()
                            val stopId = hit?.getStringProperty("id")
                            val found = if (stopId != null) latestStopsById[stopId] else null
                            selectedStopBanner = found
                            found != null
                        }
                    }

                    fun updateOverlay(style: Style) {
                        val linePoints = validStops.map { Point.fromLngLat(it.longitude, it.latitude) }
                        val lineCollection = if (linePoints.size >= 2) {
                            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(linePoints)))
                        } else {
                            FeatureCollection.fromFeatures(emptyArray())
                        }
                        val existingLine = style.getSourceAs<GeoJsonSource>(SOURCE_TIMETABLE_LINE)
                        if (existingLine != null) {
                            existingLine.setGeoJson(lineCollection)
                            style.getLayerAs<LineLayer>(LAYER_TIMETABLE_LINE)?.setProperties(
                                PropertyFactory.lineColor(lineColorHex)
                            )
                        } else {
                            style.addSource(GeoJsonSource(SOURCE_TIMETABLE_LINE, lineCollection))
                            style.addLayer(
                                LineLayer(LAYER_TIMETABLE_LINE, SOURCE_TIMETABLE_LINE).withProperties(
                                    PropertyFactory.lineColor(lineColorHex),
                                    PropertyFactory.lineWidth(4.5f),
                                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                                )
                            )
                        }

                        val stopFeatures = validStops.map { stop ->
                            Feature.fromGeometry(Point.fromLngLat(stop.longitude, stop.latitude)).apply {
                                addStringProperty("id", stop.stopId)
                                addStringProperty("name", stop.stopName)
                            }
                        }
                        val stopCollection = FeatureCollection.fromFeatures(stopFeatures)
                        val existingStops = style.getSourceAs<GeoJsonSource>(SOURCE_TIMETABLE_STOPS)
                        if (existingStops != null) {
                            existingStops.setGeoJson(stopCollection)
                            style.getLayerAs<CircleLayer>(LAYER_TIMETABLE_STOPS)?.setProperties(
                                PropertyFactory.circleStrokeColor(lineColorHex)
                            )
                        } else {
                            style.addSource(GeoJsonSource(SOURCE_TIMETABLE_STOPS, stopCollection))
                            style.addLayer(
                                CircleLayer(LAYER_TIMETABLE_STOPS, SOURCE_TIMETABLE_STOPS).withProperties(
                                    PropertyFactory.circleColor("#FFFFFF"),
                                    PropertyFactory.circleRadius(6.0f),
                                    PropertyFactory.circleStrokeWidth(2.6f),
                                    PropertyFactory.circleStrokeColor(lineColorHex)
                                )
                            )
                        }

                        if (lastFittedStops !== stops) {
                            lastFittedStops = stops
                            if (validStops.size >= 2) {
                                val builder = LatLngBounds.Builder()
                                validStops.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
                                runCatching {
                                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 72))
                                }
                            } else {
                                val center = validStops.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
                                    ?: LatLng(AppConfig.POZNAN_CENTER_LAT, AppConfig.POZNAN_CENTER_LON)
                                map.cameraPosition = CameraPosition.Builder().target(center).zoom(13.0).build()
                            }
                        }
                    }

                    val currentStyle = map.style
                    if (currentStyle == null || !currentStyle.isFullyLoaded || appliedStyleJson != styleJson) {
                        appliedStyleJson = styleJson
                        map.setStyle(Style.Builder().fromJson(styleJson)) { loadedStyle ->
                            OfflineMapStyleHelper.addPoznanLandmarksLayer(loadedStyle, isDarkTheme, density.density)
                            updateOverlay(loadedStyle)
                        }
                    } else {
                        updateOverlay(currentStyle)
                    }
                }
            }
        )

        selectedStopBanner?.let { stop ->
            val s = LocalStrings.current
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(14.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stop.stopName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${s.zonePrefix} ${stop.zoneId}" +
                                if (stop.platformCode.isNotBlank()) " • ${s.platformPrefix} ${stop.platformCode}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!stop.isTerminal) {
                        Button(
                            onClick = { onSelectStop(stop) },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(s.timetables)
                        }
                    }
                }
            }
        }
    }
}
