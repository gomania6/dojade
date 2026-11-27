package pl.poznan.offdojade.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.location.UserLocation
import pl.poznan.offdojade.data.realtime.RealtimeStatus
import pl.poznan.offdojade.ui.components.OfflineMapStyleHelper
import pl.poznan.offdojade.ui.components.RealtimeStatusBadge
import pl.poznan.offdojade.ui.localization.LocalStrings
import pl.poznan.offdojade.ui.theme.ArrivalPillBlue
import pl.poznan.offdojade.ui.theme.DelayRed
import pl.poznan.offdojade.ui.theme.LiveGreen
import pl.poznan.offdojade.ui.theme.NightBusOnWhite
import java.io.File

@Composable
fun LiveMapScreen(
    viewModel: LiveMapViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val realtimeStatus by viewModel.realtimeStatus.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Полноэкранная оффлайн-карта со всем живым транспортом и полупрозрачной линией выбранного маршрута
        LiveVehiclesMapView(
            localMapFile = state.localMapFile,
            isDarkTheme = isDarkTheme,
            vehicles = state.filteredVehicles,
            selectedVehicle = state.selectedVehicle,
            userLocation = state.userLocation,
            onMapReady = { map -> mapRef = map },
            onVehicleTapped = { vehicleId -> viewModel.selectVehicleById(vehicleId) },
            onEmptyMapTapped = { viewModel.clearSelection() },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Верхняя панель: поиск по линии, статус Live и фильтры (Все / Трамваи / Автобусы)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 6.dp,
                border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (state.lineQuery.isEmpty()) {
                            Text(
                                text = s.liveMapSearchHint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = state.lineQuery,
                            onValueChange = viewModel::onLineQueryChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (state.lineQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.onLineQueryChange("") },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = s.clear,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    RealtimeStatusBadge(status = realtimeStatus)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val chipColors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
                FilterChip(
                    selected = state.filter == LiveMapVehicleFilter.ALL,
                    onClick = { viewModel.setFilter(LiveMapVehicleFilter.ALL) },
                    label = {
                        Text(
                            text = "${s.filterAll} (${state.allVehicles.size})",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = chipColors
                )
                FilterChip(
                    selected = state.filter == LiveMapVehicleFilter.TRAMS,
                    onClick = { viewModel.setFilter(LiveMapVehicleFilter.TRAMS) },
                    leadingIcon = {
                        Icon(Icons.Default.Tram, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    label = {
                        Text(
                            text = s.filterTrams,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = chipColors
                )
                FilterChip(
                    selected = state.filter == LiveMapVehicleFilter.BUSES,
                    onClick = { viewModel.setFilter(LiveMapVehicleFilter.BUSES) },
                    leadingIcon = {
                        Icon(Icons.Default.DirectionsBus, contentDescription = null, modifier = Modifier.size(15.dp))
                    },
                    label = {
                        Text(
                            text = s.filterBuses,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = chipColors
                )
            }
        }

        // 3. Правые кнопки: Моя геопозиция и Обновить
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    viewModel.refreshUserLocation { loc ->
                        mapRef?.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(loc.latitude, loc.longitude))
                                    .zoom(14.5)
                                    .build()
                            ),
                            450
                        )
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = s.myLocation)
            }
            SmallFloatingActionButton(
                onClick = { viewModel.refreshNow() },
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(Icons.Default.Refresh, contentDescription = s.refresh)
            }
        }

        // 4. Нижняя карточка выбранного транспорта (куда едет, остановка, задержка) или подсказка
        val selected = state.selectedVehicle
        AnimatedVisibility(
            visible = selected != null,
            enter = slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(tween(160)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            if (selected != null) {
                SelectedLiveVehicleCard(
                    info = selected,
                    onDismiss = { viewModel.clearSelection() }
                )
            }
        }

        if (selected == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Text(
                    text = if (state.allVehicles.isEmpty() && realtimeStatus is RealtimeStatus.Offline) {
                        s.liveMapNoVehicles
                    } else {
                        s.liveMapTapVehicleHint
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SelectedLiveVehicleCard(
    info: SelectedMapVehicleInfo,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(info.vehicle.vehicleId, info.lastUpdatedEpochMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val ageSec = ((nowMs - info.lastUpdatedEpochMs) / 1000L).coerceAtLeast(0L)
    val ageMins = ageSec / 60L
    val remSec = ageSec % 60L
    val updatedAgoText = if (ageMins > 0L) {
        s.updatedMinSecAgo(ageMins, remSec)
    } else {
        s.updatedSecAgo(remSec)
    }

    val delayMin = info.vehicle.delaySeconds / 60
    val (scheduleStatusText, statusColor) = when {
        delayMin >= 1 -> s.vehicleLateBy(delayMin) to DelayRed
        delayMin <= -1 -> s.vehicleEarlyBy(-delayMin) to ArrivalPillBlue
        else -> s.vehicleOnSchedule to LiveGreen
    }

    val accent = Color(android.graphics.Color.parseColor(info.vehicle.colorHex))
    val isLightAccent = accent.luminance() > 0.6f

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 10.dp,
        border = BorderStroke(1.1.dp, accent.copy(alpha = 0.75f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (info.vehicle.isTram) Icons.Default.Tram else Icons.Default.DirectionsBus,
                        contentDescription = null,
                        tint = if (isLightAccent) NightBusOnWhite else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = info.vehicle.lineLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isLightAccent) NightBusOnWhite else Color.White,
                        maxLines = 1
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = if (info.headsign.isNotBlank()) {
                        "→ ${info.headsign}"
                    } else {
                        "${if (info.vehicle.isTram) s.modeTram else s.modeBus} ${info.vehicle.lineLabel}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val stopLine = when {
                    !info.currentStopName.isNullOrBlank() && !info.nextStopName.isNullOrBlank() ->
                        "${s.liveMapCurrentStop(info.currentStopName)} → ${info.nextStopName}"
                    !info.currentStopName.isNullOrBlank() ->
                        s.liveMapCurrentStop(info.currentStopName)
                    !info.nextStopName.isNullOrBlank() ->
                        s.liveMapCurrentStop(info.nextStopName)
                    else -> null
                }
                if (stopLine != null) {
                    Text(
                        text = stopLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = scheduleStatusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                        maxLines = 1
                    )
                    Text(
                        text = "• $updatedAgoText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = s.close,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun LiveVehiclesMapView(
    localMapFile: File?,
    isDarkTheme: Boolean,
    vehicles: List<EnrichedMapVehicle>,
    selectedVehicle: SelectedMapVehicleInfo?,
    userLocation: UserLocation?,
    onMapReady: (MapLibreMap) -> Unit,
    onVehicleTapped: (String) -> Unit,
    onEmptyMapTapped: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnVehicleTapped by rememberUpdatedState(onVehicleTapped)
    val latestOnEmptyTapped by rememberUpdatedState(onEmptyMapTapped)
    val latestOnMapReady by rememberUpdatedState(onMapReady)

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
    val hitRadiusPx = remember(density) { with(density) { 22.dp.toPx() } }
    var appliedStyleJson by remember { mutableStateOf<String?>(null) }
    var initializedCamera by remember { mutableStateOf(false) }
    var clickListenerAttached by remember { mutableStateOf(false) }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { mv ->
            mv.getMapAsync { map ->
                latestOnMapReady(map)
                if (!initializedCamera) {
                    initializedCamera = true
                    val startLat = userLocation?.latitude ?: AppConfig.POZNAN_CENTER_LAT
                    val startLon = userLocation?.longitude ?: AppConfig.POZNAN_CENTER_LON
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(startLat, startLon))
                        .zoom(13.2)
                        .build()
                }
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
                        val hit = map.queryRenderedFeatures(hitBox, LAYER_ALL_VEHICLES).firstOrNull()
                        val vehicleId = hit?.getStringProperty("vehicleId")
                        if (!vehicleId.isNullOrBlank()) {
                            latestOnVehicleTapped(vehicleId)
                            true
                        } else {
                            latestOnEmptyTapped()
                            false
                        }
                    }
                }

                val currentStyle = map.style
                if (currentStyle == null || !currentStyle.isFullyLoaded || appliedStyleJson != styleJson) {
                    appliedStyleJson = styleJson
                    map.setStyle(Style.Builder().fromJson(styleJson)) { loadedStyle ->
                        OfflineMapStyleHelper.addPoznanLandmarksLayer(loadedStyle, isDarkTheme, density.density)
                        renderLiveMapOverlay(
                            style = loadedStyle,
                            vehicles = vehicles,
                            selected = selectedVehicle,
                            userLocation = userLocation,
                            density = density.density
                        )
                    }
                } else {
                    renderLiveMapOverlay(
                        style = currentStyle,
                        vehicles = vehicles,
                        selected = selectedVehicle,
                        userLocation = userLocation,
                        density = density.density
                    )
                }
            }
        }
    )
}

private fun renderLiveMapOverlay(
    style: Style,
    vehicles: List<EnrichedMapVehicle>,
    selected: SelectedMapVehicleInfo?,
    userLocation: UserLocation?,
    density: Float
) {
    // 1. Полупрозрачная линия маршрута выбранного автобуса/трамвая
    val routeFeatures = if (selected != null && selected.routeCoordinates.size >= 2) {
        val pts = selected.routeCoordinates.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
        listOf(
            Feature.fromGeometry(LineString.fromLngLats(pts)).apply {
                addStringProperty("color", selected.vehicle.colorHex)
            }
        )
    } else {
        emptyList()
    }
    val routeCollection = FeatureCollection.fromFeatures(routeFeatures)
    val existingRouteSource = style.getSourceAs<GeoJsonSource>(SOURCE_SELECTED_ROUTE)
    if (existingRouteSource != null) {
        existingRouteSource.setGeoJson(routeCollection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_SELECTED_ROUTE, routeCollection))
        style.addLayer(
            LineLayer(LAYER_SELECTED_ROUTE, SOURCE_SELECTED_ROUTE).withProperties(
                PropertyFactory.lineColor(Expression.get("color")),
                PropertyFactory.lineOpacity(0.56f),
                PropertyFactory.lineWidth(5.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    // 2. Остановки вдоль выбранного маршрута
    val stopFeatures = if (selected != null) {
        val colorHex = selected.vehicle.colorHex
        selected.routeStops.map { stop ->
            Feature.fromGeometry(Point.fromLngLat(stop.longitude, stop.latitude)).apply {
                addStringProperty("color", colorHex)
            }
        }
    } else {
        emptyList()
    }
    val stopCollection = FeatureCollection.fromFeatures(stopFeatures)
    val existingStopsSource = style.getSourceAs<GeoJsonSource>(SOURCE_SELECTED_STOPS)
    if (existingStopsSource != null) {
        existingStopsSource.setGeoJson(stopCollection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_SELECTED_STOPS, stopCollection))
        style.addLayer(
            CircleLayer(LAYER_SELECTED_STOPS, SOURCE_SELECTED_STOPS).withProperties(
                PropertyFactory.circleRadius(4.2f),
                PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleOpacity(0.85f),
                PropertyFactory.circleStrokeColor(Expression.get("color")),
                PropertyFactory.circleStrokeWidth(2.2f)
            )
        )
    }

    // 3. Точка местоположения пользователя
    val userFeatures = if (userLocation != null && !userLocation.isFallbackCenter) {
        listOf(Feature.fromGeometry(Point.fromLngLat(userLocation.longitude, userLocation.latitude)))
    } else {
        emptyList()
    }
    val userCollection = FeatureCollection.fromFeatures(userFeatures)
    val existingUserSource = style.getSourceAs<GeoJsonSource>(SOURCE_USER_LOC)
    if (existingUserSource != null) {
        existingUserSource.setGeoJson(userCollection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_USER_LOC, userCollection))
        style.addLayer(
            CircleLayer(LAYER_USER_LOC, SOURCE_USER_LOC).withProperties(
                PropertyFactory.circleRadius(7.0f),
                PropertyFactory.circleColor("#2563EB"),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.8f)
            )
        )
    }

    // 4. Все активные автобусы и трамваи в реальном времени
    val selectedId = selected?.vehicle?.vehicleId
    val vehicleFeatures = ArrayList<Feature>(vehicles.size)
    // Отрисовываем выбранную машину последней, чтобы она была поверх остальных
    val ordered = if (selectedId != null) {
        vehicles.sortedBy { if (it.vehicleId == selectedId) 1 else 0 }
    } else {
        vehicles
    }
    for (v in ordered) {
        val isSelected = v.vehicleId == selectedId
        val iconId = "lm_${if (v.isTram) "t" else "b"}_${v.colorHex.removePrefix("#")}_${if (isSelected) "s" else "n"}_${v.lineLabel}"
        if (style.getImage(iconId) == null) {
            style.addImage(
                iconId,
                createCompactVehicleBitmap(
                    label = v.lineLabel,
                    isTram = v.isTram,
                    colorHex = v.colorHex,
                    isSelected = isSelected,
                    density = density
                )
            )
        }
        vehicleFeatures.add(
            Feature.fromGeometry(Point.fromLngLat(v.longitude, v.latitude)).apply {
                addStringProperty("icon", iconId)
                addStringProperty("vehicleId", v.vehicleId)
            }
        )
    }
    val vehicleCollection = FeatureCollection.fromFeatures(vehicleFeatures)
    val existingVehSource = style.getSourceAs<GeoJsonSource>(SOURCE_ALL_VEHICLES)
    if (existingVehSource != null) {
        existingVehSource.setGeoJson(vehicleCollection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_ALL_VEHICLES, vehicleCollection))
        style.addLayer(
            SymbolLayer(LAYER_ALL_VEHICLES, SOURCE_ALL_VEHICLES).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
    }
}

private fun createCompactVehicleBitmap(
    label: String,
    isTram: Boolean,
    colorHex: String,
    isSelected: Boolean,
    density: Float
): Bitmap {
    val scale = (density / 2.5f).coerceIn(0.75f, 1.4f)
    val baseSize = if (isSelected) 82f else 62f
    val size = (baseSize * scale).toInt().coerceAtLeast(48)
    val cx = size / 2f
    val cy = size / 2f
    val radius = size / 2f - (if (isSelected) 5.5f else 4f) * scale

    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val parsedBg = android.graphics.Color.parseColor(colorHex)
    val r = android.graphics.Color.red(parsedBg) / 255f
    val g = android.graphics.Color.green(parsedBg) / 255f
    val b = android.graphics.Color.blue(parsedBg) / 255f
    val isLightBg = (0.299f * r + 0.587f * g + 0.114f * b) > 0.65f
    val fgInt = if (isLightBg) android.graphics.Color.parseColor("#141820") else android.graphics.Color.WHITE
    val borderInt = when {
        isSelected -> android.graphics.Color.parseColor("#FACC15")
        isLightBg -> android.graphics.Color.parseColor("#2A303C")
        else -> android.graphics.Color.WHITE
    }

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = parsedBg
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderInt
        style = Paint.Style.STROKE
        strokeWidth = (if (isSelected) 5.5f else 3.5f) * scale
    }
    canvas.drawCircle(cx, cy, radius, bgPaint)
    canvas.drawCircle(cx, cy, radius, borderPaint)

    // Небольшой маркер типа (трамвай — полоска пантографа сверху, автобус — точка)
    if (isTram) {
        val pantograph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fgInt
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * scale
            strokeCap = Paint.Cap.ROUND
        }
        val topY = cy - radius * 0.52f
        canvas.drawLine(cx - 7f * scale, topY, cx + 7f * scale, topY, pantograph)
        canvas.drawLine(cx, topY, cx, topY + 4f * scale, pantograph)
    }

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fgInt
        val baseFont = when {
            label.length >= 4 -> if (isSelected) 18f else 14f
            label.length == 3 -> if (isSelected) 22f else 17.5f
            else -> if (isSelected) 26f else 21f
        }
        textSize = baseFont * scale
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val textY = if (isTram) {
        cy + (if (isSelected) 14f else 11f) * scale
    } else {
        cy - (textPaint.descent() + textPaint.ascent()) / 2f
    }
    canvas.drawText(label, cx, textY, textPaint)

    return bitmap
}

private const val SOURCE_SELECTED_ROUTE = "offdojade-livemap-route-source"
private const val LAYER_SELECTED_ROUTE = "offdojade-livemap-route-layer"
private const val SOURCE_SELECTED_STOPS = "offdojade-livemap-stops-source"
private const val LAYER_SELECTED_STOPS = "offdojade-livemap-stops-layer"
private const val SOURCE_USER_LOC = "offdojade-livemap-user-source"
private const val LAYER_USER_LOC = "offdojade-livemap-user-layer"
private const val SOURCE_ALL_VEHICLES = "offdojade-livemap-vehicles-source"
private const val LAYER_ALL_VEHICLES = "offdojade-livemap-vehicles-layer"
