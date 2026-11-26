package pl.poznan.offdojade.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
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
import pl.poznan.offdojade.data.realtime.LiveVehicle
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.ui.components.LineBadge
import pl.poznan.offdojade.ui.components.TicketRecommendationCard
import pl.poznan.offdojade.ui.components.TimeFormatUtils
import pl.poznan.offdojade.ui.theme.DelayRed
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailsScreen(
    onBack: () -> Unit,
    viewModel: RouteDetailsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val liveVehicles by viewModel.liveVehicles.collectAsStateWithLifecycle()
    val journey = state.journey

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = "Карта и детали поездки",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (journey != null) {
                            Text(
                                text = "${TimeFormatUtils.formatClock(journey.departureTimeSec)} → " +
                                    "${TimeFormatUtils.formatClock(journey.arrivalTimeSec)} (${journey.totalDurationMinutes} мин)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (journey == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Выберите маршрут из списка результатов")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Верхняя половина: MapLibre Native с локальным poznan.pmtiles (или резервным стилем)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                OfflineRouteMapView(
                    localMapFile = state.localMapFile,
                    journey = journey,
                    polylines = state.polylines,
                    liveVehicles = liveVehicles,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                ) {
                    Text(
                        text = if (state.localMapFile != null) "PMTiles • Офлайн" else "Базовая карта",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Нижняя половина: пошаговый таймлайн остановок + калькулятор билетов
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    TicketRecommendationCard(ticket = journey.ticketRecommendation)
                }

                items(journey.legs) { leg ->
                    when (leg) {
                        is JourneyLeg.WalkLeg -> WalkLegTimelineCard(leg)
                        is JourneyLeg.TransitLeg -> TransitLegTimelineCard(leg)
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineRouteMapView(
    localMapFile: File?,
    journey: TransitJourney,
    polylines: List<RouteShapePolyline>,
    liveVehicles: List<LiveVehicle>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        MapView(context).apply { onCreate(null) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    val styleJson = remember(localMapFile?.absolutePath) {
        buildOfflineMapStyleJson(localMapFile)
    }

    val relevantTripIds = remember(journey) {
        journey.transitLegs.map { it.tripId }.toSet()
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { mv ->
            mv.getMapAsync { map ->
                val currentStyle = map.style
                if (currentStyle == null || !currentStyle.isFullyLoaded) {
                    map.setStyle(Style.Builder().fromJson(styleJson)) { loadedStyle ->
                        renderRouteOverlay(loadedStyle, journey, polylines, liveVehicles, relevantTripIds)
                        fitCameraToJourney(map, journey, polylines)
                    }
                } else {
                    renderRouteOverlay(currentStyle, journey, polylines, liveVehicles, relevantTripIds)
                }
            }
        }
    )
}

private fun renderRouteOverlay(
    style: Style,
    journey: TransitJourney,
    polylines: List<RouteShapePolyline>,
    liveVehicles: List<LiveVehicle>,
    relevantTripIds: Set<String>
) {
    // 1. Полилинии маршрута из shapes.txt
    val lineFeatures = polylines.mapNotNull { poly ->
        if (poly.coordinates.size < 2) return@mapNotNull null
        val points = poly.coordinates.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
        Feature.fromGeometry(LineString.fromLngLats(points))
    }
    val routeCollection = FeatureCollection.fromFeatures(lineFeatures)
    val existingRouteSource = style.getSourceAs<GeoJsonSource>(SOURCE_ROUTE_LINES)
    if (existingRouteSource != null) {
        existingRouteSource.setGeoJson(routeCollection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_ROUTE_LINES, routeCollection))
        style.addLayer(
            LineLayer(LAYER_ROUTE_LINES, SOURCE_ROUTE_LINES).withProperties(
                PropertyFactory.lineColor("#007A5E"),
                PropertyFactory.lineWidth(5.0f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    // 2. Точки остановок маршрута
    val stopFeatures = journey.transitLegs.flatMap { leg ->
        leg.stops.map { stop ->
            Feature.fromGeometry(Point.fromLngLat(stop.longitude, stop.latitude))
        }
    }
    val stopCollection = FeatureCollection.fromFeatures(stopFeatures)
    val existingStopSource = style.getSourceAs<GeoJsonSource>(SOURCE_ROUTE_STOPS)
    if (existingStopSource != null) {
        existingStopSource.setGeoJson(stopCollection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_ROUTE_STOPS, stopCollection))
        style.addLayer(
            CircleLayer(LAYER_ROUTE_STOPS, SOURCE_ROUTE_STOPS).withProperties(
                PropertyFactory.circleRadius(5.0f),
                PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleStrokeColor("#006B54"),
                PropertyFactory.circleStrokeWidth(2.5f)
            )
        )
    }

    // 3. Живые маркеры транспорта из vehicle_positions.pb
    val matchingVehicles = liveVehicles.filter { it.tripId in relevantTripIds }
        .ifEmpty { liveVehicles.take(25) }
    val vehicleFeatures = matchingVehicles.map { v ->
        Feature.fromGeometry(Point.fromLngLat(v.longitude, v.latitude))
    }
    val vehicleCollection = FeatureCollection.fromFeatures(vehicleFeatures)
    val existingVehicleSource = style.getSourceAs<GeoJsonSource>(SOURCE_LIVE_VEHICLES)
    if (existingVehicleSource != null) {
        existingVehicleSource.setGeoJson(vehicleCollection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_LIVE_VEHICLES, vehicleCollection))
        style.addLayer(
            CircleLayer(LAYER_LIVE_VEHICLES, SOURCE_LIVE_VEHICLES).withProperties(
                PropertyFactory.circleRadius(7.0f),
                PropertyFactory.circleColor("#DC2626"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.0f)
            )
        )
    }
}

private fun fitCameraToJourney(
    map: org.maplibre.android.maps.MapLibreMap,
    journey: TransitJourney,
    polylines: List<RouteShapePolyline>
) {
    val allPoints = buildList {
        polylines.forEach { poly ->
            poly.coordinates.forEach { (lat, lon) -> add(LatLng(lat, lon)) }
        }
        if (isEmpty()) {
            journey.transitLegs.forEach { leg ->
                leg.stops.forEach { s -> add(LatLng(s.latitude, s.longitude)) }
            }
        }
    }

    if (allPoints.size >= 2) {
        val builder = LatLngBounds.Builder()
        allPoints.forEach { builder.include(it) }
        runCatching {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 64))
        }
    } else {
        val center = allPoints.firstOrNull()
            ?: LatLng(AppConfig.POZNAN_CENTER_LAT, AppConfig.POZNAN_CENTER_LON)
        map.cameraPosition = CameraPosition.Builder().target(center).zoom(13.0).build()
    }
}

/**
 * Генерирует стиль MapLibre:
 * - Если локальный `poznan.pmtiles` присутствует в `filesDir`, подключает его через `pmtiles://file://...`
 *   для 100% офлайн-рендеринга векторной карты.
 * - Если файл еще не скачан — возвращает чистый резервный стиль без сетевых зависимостей.
 */
private fun buildOfflineMapStyleJson(localMapFile: File?): String {
    if (localMapFile != null && localMapFile.exists()) {
        val uri = "pmtiles://file://${localMapFile.absolutePath}"
        return """
            {
              "version": 8,
              "name": "Poznan Offline PMTiles",
              "sources": {
                "poznan": {
                  "type": "vector",
                  "url": "$uri"
                }
              },
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#EEF2F0" }
                },
                {
                  "id": "water",
                  "type": "fill",
                  "source": "poznan",
                  "source-layer": "water",
                  "paint": { "fill-color": "#AADAFF" }
                },
                {
                  "id": "parks",
                  "type": "fill",
                  "source": "poznan",
                  "source-layer": "landuse",
                  "paint": { "fill-color": "#D4E8D4" }
                },
                {
                  "id": "roads",
                  "type": "line",
                  "source": "poznan",
                  "source-layer": "roads",
                  "paint": { "line-color": "#FFFFFF", "line-width": 2 }
                },
                {
                  "id": "buildings",
                  "type": "fill",
                  "source": "poznan",
                  "source-layer": "buildings",
                  "paint": { "fill-color": "#D9DFDD", "fill-opacity": 0.7 }
                }
              ]
            }
        """.trimIndent()
    }

    return """
        {
          "version": 8,
          "name": "OffDojade Fallback Style",
          "sources": {},
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "#E9EFEC" }
            }
          ]
        }
    """.trimIndent()
}

@Composable
private fun WalkLegTimelineCard(leg: JourneyLeg.WalkLeg) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DirectionsWalk,
                contentDescription = "Пешком",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Пешком до ${leg.toName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${leg.distanceMeters} м • ~${(leg.durationSec + 30) / 60} мин",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${TimeFormatUtils.formatClock(leg.startTimeSec)}–${TimeFormatUtils.formatClock(leg.endTimeSec)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TransitLegTimelineCard(leg: JourneyLeg.TransitLeg) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LineBadge(
                    shortName = leg.routeShortName,
                    isTram = leg.isTram,
                    colorHex = leg.routeColorHex
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = leg.headsign.ifBlank { "По маршруту" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${leg.stopsPassed} ост. • ${(leg.durationSec + 30) / 60} мин",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val delayText = TimeFormatUtils.formatDelayBadge(leg.delaySeconds)
                if (delayText != null) {
                    Text(
                        text = delayText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (leg.delaySeconds > 0) DelayRed else MaterialTheme.colorScheme.primary
                    )
                }
            }

            HorizontalDivider()

            leg.stops.forEachIndexed { idx, stop ->
                val isEndpoint = idx == 0 || idx == leg.stops.lastIndex
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = TimeFormatUtils.formatClock(stop.effectiveTimeSec),
                        style = if (isEndpoint) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                        fontWeight = if (isEndpoint) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.width(52.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stop.stopName,
                            style = if (isEndpoint) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                            fontWeight = if (isEndpoint) FontWeight.SemiBold else FontWeight.Normal
                        )
                        val meta = buildList {
                            if (stop.platformCode.isNotBlank()) add("Платформа ${stop.platformCode}")
                            if (stop.zoneId.isNotBlank()) add("Зона ${stop.zoneId}")
                        }.joinToString(" • ")
                        if (meta.isNotEmpty() && isEndpoint) {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SOURCE_ROUTE_LINES = "offdojade-route-lines-source"
private const val LAYER_ROUTE_LINES = "offdojade-route-lines-layer"
private const val SOURCE_ROUTE_STOPS = "offdojade-route-stops-source"
private const val LAYER_ROUTE_STOPS = "offdojade-route-stops-layer"
private const val SOURCE_LIVE_VEHICLES = "offdojade-live-vehicles-source"
private const val LAYER_LIVE_VEHICLES = "offdojade-live-vehicles-layer"
