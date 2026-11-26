package pl.poznan.offdojade.ui.details

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import pl.poznan.offdojade.data.realtime.LiveVehicle
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.ui.components.TicketRecommendationCard
import pl.poznan.offdojade.ui.components.TimeFormatUtils
import pl.poznan.offdojade.ui.localization.LocalStrings
import pl.poznan.offdojade.ui.theme.ArrivalPillBlue
import pl.poznan.offdojade.ui.theme.DelayRed
import pl.poznan.offdojade.ui.theme.DeparturePillGreen
import pl.poznan.offdojade.ui.theme.LiveGreen
import pl.poznan.offdojade.ui.theme.OfflineGray
import java.io.File
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RouteDetailsScreen(
    onBack: () -> Unit,
    viewModel: RouteDetailsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val liveVehicles by viewModel.liveVehicles.collectAsStateWithLifecycle()
    val journey = state.journey
    val s = LocalStrings.current
    val nowSec = remember { LocalTime.now().toSecondOfDay() }

    // Панель таймлайна справа: true = раскрыта (~90%), false = свёрнута (~23%, открыта карта)
    var isPanelExpanded by remember { mutableStateOf(true) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
                        }
                    },
                    title = {
                        Text(
                            text = s.mapAndDetailsTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions = {
                        if (journey != null) {
                            IconButton(onClick = { isPanelExpanded = !isPanelExpanded }) {
                                Icon(
                                    imageVector = if (isPanelExpanded) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                                    contentDescription = null
                                )
                            }
                        }
                    }
                )

                // Верхняя сводка маршрута (как на скриншотах Jakdojade)
                if (journey != null) {
                    JourneyHeaderSummary(
                        journey = journey,
                        referenceNowSec = nowSec
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    ) { padding ->
        if (journey == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(s.selectRouteFromList)
            }
            return@Scaffold
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val totalWidth = maxWidth
            val panelWidth by animateDpAsState(
                targetValue = if (isPanelExpanded) totalWidth * 0.90f else 88.dp,
                animationSpec = tween(durationMillis = 240),
                label = "detailsPanelWidth"
            )

            // 1. Полноэкранная карта MapLibre на заднем плане
            OfflineRouteMapView(
                localMapFile = state.localMapFile,
                journey = journey,
                polylines = state.polylines,
                outsidePolylines = state.outsidePolylines,
                walkPolylines = state.walkPolylines,
                liveVehicles = liveVehicles,
                modifier = Modifier.fillMaxSize()
            )

            // Если панель раскрыта — клик по видимой полоске карты слева сворачивает панель
            if (isPanelExpanded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(totalWidth - panelWidth)
                        .fillMaxHeight()
                        .clickable { isPanelExpanded = false }
                )
            } else {
                // Бейдж карты в левом верхнем углу, когда карта открыта
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                ) {
                    Text(
                        text = if (state.localMapFile != null) s.pmTilesOffline else s.baseMap,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // 2. Выдвижная правая панель таймлайна (раскрывается/сворачивается свайпом или тапом)
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(panelWidth)
                    .fillMaxHeight()
                    .pointerInput(isPanelExpanded) {
                        var dragAccum = 0f
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragAccum > 40f && isPanelExpanded) {
                                    isPanelExpanded = false
                                } else if (dragAccum < -40f && !isPanelExpanded) {
                                    isPanelExpanded = true
                                }
                                dragAccum = 0f
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                dragAccum += dragAmount
                            }
                        )
                    },
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 10.dp,
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                if (isPanelExpanded) {
                    ExpandedTimelinePanel(journey = journey)
                } else {
                    CollapsedTimelineRail(
                        journey = journey,
                        onExpand = { isPanelExpanded = true }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JourneyHeaderSummary(
    journey: TransitJourney,
    referenceNowSec: Int
) {
    val s = LocalStrings.current
    val isDeparted = journey.departureTimeSec < referenceNowSec
    val deltaSec = journey.departureTimeSec - referenceNowSec
    val deltaMins = if (isDeparted) ((-deltaSec) + 30) / 60 else deltaSec / 60
    val isOverHour = deltaMins > 60
    val hasRealtime = journey.transitLegs.any { it.delaySeconds != 0 }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Левая колонка: Отправляется <1 мин
        Column(modifier = Modifier.width(96.dp)) {
            Text(
                text = if (isDeparted) s.departedLabel else if (isOverHour) s.onSchedule else s.departingCaption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (isOverHour) {
                    Text(
                        text = TimeFormatUtils.formatClock(journey.departureTimeSec),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = when {
                            isDeparted -> "-${deltaMins.coerceAtLeast(1)}"
                            deltaMins == 0 -> "<1"
                            else -> "$deltaMins"
                        },
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 32.sp,
                        color = if (isDeparted) OfflineGray else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = s.minShort,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp)
                    )
                }
                if (!isDeparted && hasRealtime) {
                    Icon(
                        imageVector = Icons.Default.Sensors,
                        contentDescription = null,
                        tint = DelayRed,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(bottom = 3.dp)
                    )
                }
            }
        }

        // Центр: линии + зелёная/синяя капсулы времени
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                journey.transitLegs.forEach { leg ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (leg.isTram) Icons.Default.Tram else Icons.Default.DirectionsBus,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                text = leg.routeShortName,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SmallTimePill(TimeFormatUtils.formatClock(journey.departureTimeSec), DeparturePillGreen)
                Text(
                    text = "${journey.totalDurationMinutes} ${s.minShort}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SmallTimePill(TimeFormatUtils.formatClock(journey.arrivalTimeSec), ArrivalPillBlue)
            }
        }

        Text(
            text = "${journey.totalDurationMinutes} ${s.minShort}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExpandedTimelinePanel(journey: TransitJourney) {
    val expandedLegs = remember { mutableStateMapOf<Int, Boolean>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(journey.legs) { index, leg ->
            when (leg) {
                is JourneyLeg.WalkLeg -> JakdojadeWalkLegRow(leg)
                is JourneyLeg.TransitLeg -> JakdojadeTransitLegBlock(
                    leg = leg,
                    expanded = expandedLegs[index] == true,
                    onToggleExpand = { expandedLegs[index] = !(expandedLegs[index] == true) }
                )
            }
        }

        item(key = "ticket_card") {
            Spacer(Modifier.height(4.dp))
            TicketRecommendationCard(ticket = journey.ticketRecommendation)
        }
    }
}

@Composable
private fun CollapsedTimelineRail(
    journey: TransitJourney,
    onExpand: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onExpand),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(journey.legs) { _, leg ->
            when (leg) {
                is JourneyLeg.WalkLeg -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                            contentDescription = null,
                            tint = ArrivalPillBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(28.dp)
                                .background(ArrivalPillBlue.copy(alpha = 0.55f))
                        )
                    }
                }

                is JourneyLeg.TransitLeg -> {
                    val accent = if (leg.isTram) DeparturePillGreen else ArrivalPillBlue
                    val hasDelay = leg.delaySeconds >= 60
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Время посадки
                        SmallTimePill(
                            text = TimeFormatUtils.formatClock(leg.boardingStop.scheduledTimeSec),
                            bgColor = DeparturePillGreen,
                            strikethrough = hasDelay
                        )
                        if (hasDelay) {
                            Text(
                                text = TimeFormatUtils.formatClock(leg.boardingStop.effectiveTimeSec),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = DelayRed
                            )
                        }

                        // Круглый значок транспорта + вертикальная линия
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (leg.isTram) Icons.Default.Tram else Icons.Default.DirectionsBus,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(54.dp)
                                .background(accent)
                        )

                        // Время высадки
                        SmallTimePill(
                            text = TimeFormatUtils.formatClock(leg.alightingStop.scheduledTimeSec),
                            bgColor = ArrivalPillBlue,
                            strikethrough = hasDelay
                        )
                        if (hasDelay) {
                            Text(
                                text = TimeFormatUtils.formatClock(leg.alightingStop.effectiveTimeSec),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = DelayRed
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JakdojadeWalkLegRow(leg: JourneyLeg.WalkLeg) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.width(74.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(ArrivalPillBlue.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                    contentDescription = null,
                    tint = ArrivalPillBlue,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = s.walkTo(leg.toName),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${leg.distanceMeters} ${s.metersShort} • ~${(leg.durationSec + 30) / 60} ${s.minShort}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun JakdojadeTransitLegBlock(
    leg: JourneyLeg.TransitLeg,
    expanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val s = LocalStrings.current
    val accent = if (leg.isTram) DeparturePillGreen else ArrivalPillBlue
    val hasDelay = leg.delaySeconds >= 60
    val delayText = TimeFormatUtils.formatDelayBadge(leg.delaySeconds, s)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 1. Остановка посадки
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.width(74.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SmallTimePill(
                    text = TimeFormatUtils.formatClock(leg.boardingStop.scheduledTimeSec),
                    bgColor = DeparturePillGreen,
                    strikethrough = hasDelay
                )
                if (hasDelay) {
                    Text(
                        text = TimeFormatUtils.formatClock(leg.boardingStop.effectiveTimeSec),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = DelayRed
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = leg.boardingStop.stopName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (delayText != null) {
                    Text(
                        text = delayText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (leg.delaySeconds > 0) DelayRed else LiveGreen
                    )
                }
            }
        }

        // 2. Средняя часть: вертикальная линия транспорта + номер линии + направление + промежуточные остановки
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.width(74.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (leg.isTram) Icons.Default.Tram else Icons.Default.DirectionsBus,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(if (expanded) (leg.stops.size * 26).coerceAtLeast(56).dp else 64.dp)
                        .background(accent)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                text = leg.routeShortName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "→ ${leg.headsign.ifBlank { s.onRoute }}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "${(leg.durationSec + 30) / 60} ${s.minShort}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = leg.stops.size > 2, onClick = onToggleExpand)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${leg.stopsPassed} ${s.stopsShort}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (leg.stops.size > 2) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (expanded && leg.stops.size > 2) {
                    leg.stops.subList(1, leg.stops.lastIndex).forEach { midStop ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = TimeFormatUtils.formatClock(midStop.effectiveTimeSec),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = midStop.stopName,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // 3. Остановка высадки
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.width(74.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SmallTimePill(
                    text = TimeFormatUtils.formatClock(leg.alightingStop.scheduledTimeSec),
                    bgColor = ArrivalPillBlue,
                    strikethrough = hasDelay
                )
                if (hasDelay) {
                    Text(
                        text = TimeFormatUtils.formatClock(leg.alightingStop.effectiveTimeSec),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = DelayRed
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = leg.alightingStop.stopName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val meta = buildList {
                    if (leg.alightingStop.platformCode.isNotBlank()) add("${s.platformPrefix} ${leg.alightingStop.platformCode}")
                    if (leg.alightingStop.zoneId.isNotBlank()) add("${s.zonePrefix} ${leg.alightingStop.zoneId}")
                }.joinToString(" • ")
                if (meta.isNotEmpty()) {
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

@Composable
private fun SmallTimePill(
    text: String,
    bgColor: Color,
    strikethrough: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = bgColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            textDecoration = if (strikethrough) TextDecoration.LineThrough else TextDecoration.None,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun OfflineRouteMapView(
    localMapFile: File?,
    journey: TransitJourney,
    polylines: List<RouteShapePolyline>,
    outsidePolylines: List<RouteShapePolyline>,
    walkPolylines: List<List<Pair<Double, Double>>>,
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
                        renderRouteOverlay(
                            style = loadedStyle,
                            journey = journey,
                            polylines = polylines,
                            outsidePolylines = outsidePolylines,
                            walkPolylines = walkPolylines,
                            liveVehicles = liveVehicles,
                            relevantTripIds = relevantTripIds
                        )
                        fitCameraToJourney(map, journey, polylines, walkPolylines)
                    }
                } else {
                    renderRouteOverlay(
                        style = currentStyle,
                        journey = journey,
                        polylines = polylines,
                        outsidePolylines = outsidePolylines,
                        walkPolylines = walkPolylines,
                        liveVehicles = liveVehicles,
                        relevantTripIds = relevantTripIds
                    )
                }
            }
        }
    )
}

private fun renderRouteOverlay(
    style: Style,
    journey: TransitJourney,
    polylines: List<RouteShapePolyline>,
    outsidePolylines: List<RouteShapePolyline>,
    walkPolylines: List<List<Pair<Double, Double>>>,
    liveVehicles: List<LiveVehicle>,
    relevantTripIds: Set<String>
) {
    // 1. Полупрозрачная трасса транспорта вне нужного маршрута (максимум до ближайшей остановки)
    val outsideFeatures = outsidePolylines.mapNotNull { poly ->
        if (poly.coordinates.size < 2) return@mapNotNull null
        val points = poly.coordinates.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
        Feature.fromGeometry(LineString.fromLngLats(points))
    }
    val outsideCollection = FeatureCollection.fromFeatures(outsideFeatures)
    val existingOutsideSource = style.getSourceAs<GeoJsonSource>(SOURCE_ROUTE_OUTSIDE)
    if (existingOutsideSource != null) {
        existingOutsideSource.setGeoJson(outsideCollection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_ROUTE_OUTSIDE, outsideCollection))
        style.addLayer(
            LineLayer(LAYER_ROUTE_OUTSIDE, SOURCE_ROUTE_OUTSIDE).withProperties(
                PropertyFactory.lineColor("#16A34A"),
                PropertyFactory.lineOpacity(0.32f),
                PropertyFactory.lineWidth(3.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    // 2. Активные полилинии маршрута (от посадки до высадки — 100% непрозрачность)
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
                PropertyFactory.lineColor("#16A34A"),
                PropertyFactory.lineOpacity(1.0f),
                PropertyFactory.lineWidth(5.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    // 3. Пеший путь до остановки / пересадки / адреса — пунктирной линией
    val walkFeatures = walkPolylines.mapNotNull { seg ->
        if (seg.size < 2) return@mapNotNull null
        val points = seg.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
        Feature.fromGeometry(LineString.fromLngLats(points))
    }
    val walkCollection = FeatureCollection.fromFeatures(walkFeatures)
    val existingWalkSource = style.getSourceAs<GeoJsonSource>(SOURCE_WALK_LINES)
    if (existingWalkSource != null) {
        existingWalkSource.setGeoJson(walkCollection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_WALK_LINES, walkCollection))
        style.addLayer(
            LineLayer(LAYER_WALK_LINES, SOURCE_WALK_LINES).withProperties(
                PropertyFactory.lineColor("#0EA5E9"),
                PropertyFactory.lineWidth(4.0f),
                PropertyFactory.lineDasharray(arrayOf(1.5f, 2.0f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    // 4. Точки остановок маршрута
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
                PropertyFactory.circleRadius(5.5f),
                PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleStrokeColor("#16A34A"),
                PropertyFactory.circleStrokeWidth(2.5f)
            )
        )
    }

    // 5. Живые маркеры транспорта со значком трамвая/автобуса и номером линии
    val legByTripId = journey.transitLegs.associateBy { it.tripId }
    val matchingVehicles = liveVehicles.filter { it.tripId in relevantTripIds }
        .ifEmpty { liveVehicles.take(25) }

    val vehicleFeatures = matchingVehicles.map { v ->
        val leg = legByTripId[v.tripId]
        val label = leg?.routeShortName ?: v.routeId?.take(4)?.ifBlank { "ZTM" } ?: "ZTM"
        val isTram = leg?.isTram ?: (label.toIntOrNull()?.let { it in 0..99 || it in 201..202 } ?: false)
        val iconId = "veh_${if (isTram) "tram" else "bus"}_$label"
        if (style.getImage(iconId) == null) {
            style.addImage(iconId, createVehicleBadgeBitmap(label = label, isTram = isTram))
        }
        Feature.fromGeometry(Point.fromLngLat(v.longitude, v.latitude)).apply {
            addStringProperty("icon", iconId)
        }
    }
    val vehicleCollection = FeatureCollection.fromFeatures(vehicleFeatures)
    val existingVehicleSource = style.getSourceAs<GeoJsonSource>(SOURCE_LIVE_VEHICLES)
    if (existingVehicleSource != null) {
        existingVehicleSource.setGeoJson(vehicleCollection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_LIVE_VEHICLES, vehicleCollection))
        style.addLayer(
            SymbolLayer(LAYER_LIVE_VEHICLES, SOURCE_LIVE_VEHICLES).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
    }
}

/**
 * Генерирует чёткий бейдж транспорта (значок трамвая/автобуса + номер линии) для отображения на карте.
 */
private fun createVehicleBadgeBitmap(label: String, isTram: Boolean): Bitmap {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val textWidth = textPaint.measureText(label)
    val iconArea = 42f
    val padX = 18f
    val width = (padX + iconArea + textWidth + padX).toInt().coerceAtLeast(112)
    val height = 62

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isTram) android.graphics.Color.parseColor("#16A34A") else android.graphics.Color.parseColor("#0EA5E9")
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    val rect = RectF(3f, 3f, width - 3f, height - 3f)
    canvas.drawRoundRect(rect, 14f, 14f, bgPaint)
    canvas.drawRoundRect(rect, 14f, 14f, borderPaint)

    // Значок трамвая (с пантографом) или автобуса
    val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    val fillIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    val ix = padX + 4f
    val bodyRect = RectF(ix, 17f, ix + 24f, 43f)
    canvas.drawRoundRect(bodyRect, 4f, 4f, iconPaint)
    // Лобовое стекло
    canvas.drawRect(ix + 4f, 21f, ix + 20f, 31f, fillIconPaint)
    // Фары
    canvas.drawCircle(ix + 6f, 37f, 2.2f, fillIconPaint)
    canvas.drawCircle(ix + 18f, 37f, 2.2f, fillIconPaint)
    if (isTram) {
        // Пантограф сверху для трамвая
        canvas.drawLine(ix + 12f, 17f, ix + 12f, 11f, iconPaint)
        canvas.drawLine(ix + 6f, 11f, ix + 18f, 11f, iconPaint)
    } else {
        // Колёса снизу для автобуса
        canvas.drawCircle(ix + 6f, 46f, 2.5f, fillIconPaint)
        canvas.drawCircle(ix + 18f, 46f, 2.5f, fillIconPaint)
    }

    // Номер линии
    val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(label, padX + iconArea, baseline, textPaint)

    return bitmap
}

private fun fitCameraToJourney(
    map: org.maplibre.android.maps.MapLibreMap,
    journey: TransitJourney,
    polylines: List<RouteShapePolyline>,
    walkPolylines: List<List<Pair<Double, Double>>>
) {
    val allPoints = buildList {
        polylines.forEach { poly ->
            poly.coordinates.forEach { (lat, lon) -> add(LatLng(lat, lon)) }
        }
        walkPolylines.forEach { seg ->
            seg.forEach { (lat, lon) -> add(LatLng(lat, lon)) }
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
                  "paint": { "background-color": "#111827" }
                },
                {
                  "id": "water",
                  "type": "fill",
                  "source": "poznan",
                  "source-layer": "water",
                  "paint": { "fill-color": "#1E3A5F" }
                },
                {
                  "id": "parks",
                  "type": "fill",
                  "source": "poznan",
                  "source-layer": "landuse",
                  "paint": { "fill-color": "#132A22" }
                },
                {
                  "id": "roads",
                  "type": "line",
                  "source": "poznan",
                  "source-layer": "roads",
                  "paint": { "line-color": "#374151", "line-width": 2 }
                },
                {
                  "id": "buildings",
                  "type": "fill",
                  "source": "poznan",
                  "source-layer": "buildings",
                  "paint": { "fill-color": "#1F2937", "fill-opacity": 0.85 }
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
              "paint": { "background-color": "#0F172A" }
            }
          ]
        }
    """.trimIndent()
}

private const val SOURCE_ROUTE_OUTSIDE = "offdojade-route-outside-source"
private const val LAYER_ROUTE_OUTSIDE = "offdojade-route-outside-layer"
private const val SOURCE_ROUTE_LINES = "offdojade-route-lines-source"
private const val LAYER_ROUTE_LINES = "offdojade-route-lines-layer"
private const val SOURCE_WALK_LINES = "offdojade-walk-lines-source"
private const val LAYER_WALK_LINES = "offdojade-walk-lines-layer"
private const val SOURCE_ROUTE_STOPS = "offdojade-route-stops-source"
private const val LAYER_ROUTE_STOPS = "offdojade-route-stops-layer"
private const val SOURCE_LIVE_VEHICLES = "offdojade-live-vehicles-source"
private const val LAYER_LIVE_VEHICLES = "offdojade-live-vehicles-layer"
