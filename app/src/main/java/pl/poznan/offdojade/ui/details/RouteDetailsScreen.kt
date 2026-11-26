package pl.poznan.offdojade.ui.details

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
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
import pl.poznan.offdojade.data.realtime.RealtimeStatus
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.domain.routing.TransitLegInsight
import pl.poznan.offdojade.ui.components.AnimatedLiveSignal
import pl.poznan.offdojade.ui.components.TicketRecommendationCard
import pl.poznan.offdojade.ui.components.TimeFormatUtils
import pl.poznan.offdojade.ui.localization.LocalStrings
import pl.poznan.offdojade.ui.theme.ArrivalPillBlue
import pl.poznan.offdojade.ui.theme.DelayAmber
import pl.poznan.offdojade.ui.theme.DelayRed
import pl.poznan.offdojade.ui.theme.DeparturePillGreen
import pl.poznan.offdojade.ui.theme.LiveGreen
import pl.poznan.offdojade.ui.theme.NightBusOnWhite
import pl.poznan.offdojade.ui.theme.OfflineGray
import java.io.File
import java.time.LocalTime
import java.time.ZoneId

private data class SelectedVehiclePopup(
    val label: String,
    val isTram: Boolean,
    val delaySeconds: Int,
    val lastUpdatedEpochMs: Long,
    val colorHex: String
)

private data class SelectedStopPopup(
    val stopName: String,
    val timeText: String,
    val routeName: String,
    val zoneId: String,
    val isOnDemand: Boolean,
    val colorHex: String
)

private sealed interface TimelineDisplayItem {
    data class WalkOrWait(
        val walkLeg: JourneyLeg.WalkLeg?,
        val waitSeconds: Int
    ) : TimelineDisplayItem

    data class Transit(
        val leg: JourneyLeg.TransitLeg,
        val legIndex: Int,
        val accentColor: Color
    ) : TimelineDisplayItem
}

private fun parseHexColor(hex: String): Color =
    Color(android.graphics.Color.parseColor(hex))

private fun buildTimelineItems(journey: TransitJourney): List<TimelineDisplayItem> {
    val result = ArrayList<TimelineDisplayItem>(journey.legs.size + 2)
    var transitIdx = 0
    for (i in journey.legs.indices) {
        when (val leg = journey.legs[i]) {
            is JourneyLeg.WalkLeg -> {
                val nextTransit = journey.legs.getOrNull(i + 1) as? JourneyLeg.TransitLeg
                val waitSec = if (nextTransit != null) {
                    (nextTransit.startTimeSec - leg.endTimeSec).coerceAtLeast(0)
                } else {
                    0
                }
                result.add(TimelineDisplayItem.WalkOrWait(walkLeg = leg, waitSeconds = waitSec))
            }

            is JourneyLeg.TransitLeg -> {
                val prevTransit = journey.legs.getOrNull(i - 1) as? JourneyLeg.TransitLeg
                if (prevTransit != null) {
                    // Пересадка на той же остановке — идти никуда не нужно, просто ждать
                    val waitSec = (leg.startTimeSec - prevTransit.endTimeSec).coerceAtLeast(0)
                    result.add(TimelineDisplayItem.WalkOrWait(walkLeg = null, waitSeconds = waitSec))
                }
                val color = parseHexColor(
                    RouteLegColors.hexForLeg(
                        transitLegIndex = transitIdx,
                        shortName = leg.routeShortName,
                        isTram = leg.isTram
                    )
                )
                result.add(TimelineDisplayItem.Transit(leg = leg, legIndex = transitIdx, accentColor = color))
                transitIdx++
            }
        }
    }
    return result
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RouteDetailsScreen(
    onBack: () -> Unit,
    viewModel: RouteDetailsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val liveVehicles by viewModel.liveVehicles.collectAsStateWithLifecycle()
    val realtimeStatus by viewModel.realtimeStatus.collectAsStateWithLifecycle()
    val journey = state.journey
    val s = LocalStrings.current
    var nowSec by remember {
        mutableIntStateOf(LocalTime.now(ZoneId.of("Europe/Warsaw")).toSecondOfDay())
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            nowSec = LocalTime.now(ZoneId.of("Europe/Warsaw")).toSecondOfDay()
        }
    }

    // Панель таймлайна справа: true = раскрыта (~90%), false = свёрнута (~88dp, открыта карта)
    var isPanelExpanded by remember { mutableStateOf(true) }
    var selectedVehicle by remember { mutableStateOf<SelectedVehiclePopup?>(null) }
    var selectedStop by remember { mutableStateOf<SelectedStopPopup?>(null) }

    val fallbackFeedUpdatedMs = (realtimeStatus as? RealtimeStatus.Live)?.lastUpdatedEpochMs
        ?: System.currentTimeMillis()

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

        val timelineItems = remember(journey) { buildTimelineItems(journey) }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val totalWidth = maxWidth
            val expandedWidth = totalWidth * 0.90f
            val collapsedWidth = 88.dp
            val expandFraction by animateFloatAsState(
                targetValue = if (isPanelExpanded) 1f else 0f,
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                label = "detailsPanelFraction"
            )
            val panelWidth = lerp(collapsedWidth, expandedWidth, expandFraction)

            // 1. Полноэкранная карта MapLibre на заднем плане
            OfflineRouteMapView(
                localMapFile = state.localMapFile,
                journey = journey,
                polylines = state.polylines,
                outsidePolylines = state.outsidePolylines,
                walkPolylines = state.walkPolylines,
                liveVehicles = liveVehicles,
                fallbackFeedUpdatedMs = fallbackFeedUpdatedMs,
                getTripDelaySec = viewModel::getTripDelaySeconds,
                onVehicleClicked = { popup ->
                    selectedVehicle = popup
                    if (popup != null) selectedStop = null
                },
                onStopClicked = { stopPopup ->
                    selectedStop = stopPopup
                    if (stopPopup != null) selectedVehicle = null
                },
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

                // Всплывающая карточка при нажатии на кружок автобуса/трамвая на карте
                AnimatedVisibility(
                    visible = selectedVehicle != null,
                    enter = slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(tween(160)),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 14.dp, end = 100.dp)
                ) {
                    val popup = selectedVehicle
                    if (popup != null) {
                        VehicleInfoPopupCard(
                            popup = popup,
                            onDismiss = { selectedVehicle = null }
                        )
                    }
                }

                // Всплывающая карточка при нажатии на кружок остановки на карте
                AnimatedVisibility(
                    visible = selectedStop != null && selectedVehicle == null,
                    enter = slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(200)),
                    exit = slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { it / 2 } + fadeOut(tween(160)),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 14.dp, end = 100.dp)
                ) {
                    val stopPopup = selectedStop
                    if (stopPopup != null) {
                        StopInfoPopupCard(
                            popup = stopPopup,
                            onDismiss = { selectedStop = null }
                        )
                    }
                }
            }

            // 2. Выдвижная правая панель таймлайна (без перелома текста при открытии/закрытии)
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(panelWidth)
                    .fillMaxHeight()
                    .clipToBounds()
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
                Box(modifier = Modifier.fillMaxSize()) {
                    if (expandFraction > 0.02f) {
                        Box(
                            modifier = Modifier
                                .requiredWidth(expandedWidth)
                                .fillMaxHeight()
                                .align(Alignment.CenterStart)
                                .graphicsLayer {
                                    alpha = ((expandFraction - 0.12f) / 0.88f).coerceIn(0f, 1f)
                                }
                        ) {
                            ExpandedTimelinePanel(
                                journey = journey,
                                items = timelineItems,
                                legInsights = state.legInsights
                            )
                        }
                    }
                    if (expandFraction < 0.98f) {
                        Box(
                            modifier = Modifier
                                .requiredWidth(collapsedWidth)
                                .fillMaxHeight()
                                .align(Alignment.CenterStart)
                                .graphicsLayer {
                                    alpha = (1f - expandFraction * 2.2f).coerceIn(0f, 1f)
                                }
                        ) {
                            CollapsedTimelineRail(
                                items = timelineItems,
                                onExpand = { isPanelExpanded = true }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleInfoPopupCard(
    popup: SelectedVehiclePopup,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Живой таймер "сколько минут и секунд назад" обновляется каждую секунду
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(popup) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val s = LocalStrings.current
    val ageSec = ((nowMs - popup.lastUpdatedEpochMs) / 1000L).coerceAtLeast(0L)
    val ageMins = ageSec / 60L
    val remSec = ageSec % 60L
    val updatedAgoText = if (ageMins > 0L) {
        s.updatedMinSecAgo(ageMins, remSec)
    } else {
        s.updatedSecAgo(remSec)
    }

    val delayMin = popup.delaySeconds / 60
    val (scheduleStatusText, statusColor) = when {
        delayMin >= 1 -> s.vehicleLateBy(delayMin) to DelayRed
        delayMin <= -1 -> s.vehicleEarlyBy(-delayMin) to ArrivalPillBlue
        else -> s.vehicleOnSchedule to LiveGreen
    }

    val accent = parseHexColor(popup.colorHex)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (popup.isTram) Icons.Default.Tram else Icons.Default.DirectionsBus,
                    contentDescription = null,
                    tint = if (accent.luminance() > 0.6f) NightBusOnWhite else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${if (popup.isTram) s.modeTram else s.modeBus} ${popup.label}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = scheduleStatusText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
                Text(
                    text = updatedAgoText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = s.close,
                    modifier = Modifier.size(16.dp)
                )
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
    val busDepSec = journey.transitDepartureTimeSec
    val isDeparted = busDepSec < referenceNowSec
    val deltaSec = busDepSec - referenceNowSec
    val deltaMins = if (isDeparted) ((-deltaSec) + 30) / 60 else deltaSec / 60
    val isOverHour = deltaMins > 60
    val isDelayed = journey.transitLegs.any { it.delaySeconds >= 60 }
    val hasRealtime = journey.transitLegs.any { it.delaySeconds != 0 }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                        text = TimeFormatUtils.formatClock(busDepSec),
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
                    AnimatedLiveSignal(
                        isDelayed = isDelayed,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                journey.transitLegs.forEachIndexed { idx, leg ->
                    val legColor = parseHexColor(
                        RouteLegColors.hexForLeg(
                            transitLegIndex = idx,
                            shortName = leg.routeShortName,
                            isTram = leg.isTram
                        )
                    )
                    val isLightAccent = legColor.luminance() > 0.6f
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (leg.isTram) Icons.Default.Tram else Icons.Default.DirectionsBus,
                            contentDescription = null,
                            tint = legColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isLightAccent) legColor else legColor.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, legColor)
                        ) {
                            Text(
                                text = leg.routeShortName,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (isLightAccent) NightBusOnWhite else MaterialTheme.colorScheme.onSurface,
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
                SmallTimePill(TimeFormatUtils.formatClock(busDepSec), DeparturePillGreen)
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
private fun VerticalDottedLine(
    color: Color,
    height: Dp? = null,
    modifier: Modifier = Modifier
) {
    val sizedModifier = if (height != null) {
        modifier.width(6.dp).height(height)
    } else {
        modifier.width(6.dp)
    }
    ComposeCanvas(modifier = sizedModifier) {
        val dotRadius = 1.6.dp.toPx()
        val step = 5.5.dp.toPx()
        val cx = size.width / 2f
        var y = dotRadius + 1.5.dp.toPx()
        while (y <= size.height - dotRadius - 1.5.dp.toPx()) {
            drawCircle(
                color = color,
                radius = dotRadius,
                center = Offset(cx, y)
            )
            y += step
        }
    }
}

@Composable
private fun ExpandedTimelinePanel(
    journey: TransitJourney,
    items: List<TimelineDisplayItem>,
    legInsights: Map<String, TransitLegInsight>
) {
    val expandedLegs = remember { mutableStateMapOf<Int, Boolean>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        itemsIndexed(items) { index, item ->
            val nextWalk = items.getOrNull(index + 1) as? TimelineDisplayItem.WalkOrWait
            when (item) {
                is TimelineDisplayItem.WalkOrWait -> JakdojadeWalkOrWaitRow(
                    item = item,
                    hasPrev = index > 0,
                    hasNext = index < items.lastIndex
                )
                is TimelineDisplayItem.Transit -> JakdojadeTransitLegBlock(
                    leg = item.leg,
                    accent = item.accentColor,
                    insight = legInsights[item.leg.tripId],
                    expanded = expandedLegs[index] == true,
                    onToggleExpand = { expandedLegs[index] = !(expandedLegs[index] == true) },
                    nextWalkOrWait = nextWalk
                )
            }
        }

        item(key = "ticket_card") {
            Spacer(Modifier.height(14.dp))
            TicketRecommendationCard(ticket = journey.ticketRecommendation)
        }
    }
}

@Composable
private fun CollapsedTimelineRail(
    items: List<TimelineDisplayItem>,
    onExpand: () -> Unit
) {
    val s = LocalStrings.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onExpand),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(items) { index, item ->
            when (item) {
                is TimelineDisplayItem.WalkOrWait -> {
                    val needsWalk = item.walkLeg != null && item.walkLeg.distanceMeters > 15
                    val waitMins = (item.waitSeconds + 30) / 60
                    val dotColor = if (needsWalk) {
                        ArrivalPillBlue.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (index > 0) {
                            VerticalDottedLine(color = dotColor, height = 14.dp)
                        }
                        Icon(
                            imageVector = if (needsWalk) Icons.AutoMirrored.Filled.DirectionsWalk else Icons.Default.Schedule,
                            contentDescription = null,
                            tint = if (needsWalk) ArrivalPillBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        if (!needsWalk) {
                            Text(
                                text = if (waitMins > 0) "${s.waitShort} $waitMins${s.minShort}" else s.waitShort,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        if (index < items.lastIndex) {
                            VerticalDottedLine(color = dotColor, height = 14.dp)
                        }
                    }
                }

                is TimelineDisplayItem.Transit -> {
                    val leg = item.leg
                    val accent = item.accentColor
                    val hasDelay = leg.delaySeconds >= 60
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
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
                                tint = if (accent.luminance() > 0.6f) NightBusOnWhite else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(54.dp)
                                .background(accent)
                        )

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
private fun JakdojadeWalkOrWaitRow(
    item: TimelineDisplayItem.WalkOrWait,
    hasPrev: Boolean,
    hasNext: Boolean
) {
    val s = LocalStrings.current
    val walkLeg = item.walkLeg
    val needsWalk = walkLeg != null && walkLeg.distanceMeters > 15
    val walkMins = if (needsWalk) ((walkLeg!!.durationSec + 30) / 60).coerceAtLeast(1) else 0
    val waitMins = (item.waitSeconds + 30) / 60

    val titleText = when {
        !needsWalk && waitMins > 0 -> s.waitMinutes(waitMins)
        !needsWalk -> s.waitOnly
        waitMins > 0 -> s.walkAndWait(walkMins, waitMins)
        else -> s.walkMinutesOnly(walkMins)
    }

    val subtitleText = if (needsWalk && walkLeg != null) {
        "${walkLeg.distanceMeters} ${s.metersShort} • ${s.walkTo(walkLeg.toName)}"
    } else {
        s.transferSameStop
    }

    val dotColor = if (needsWalk) {
        ArrivalPillBlue.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.width(74.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (hasPrev) {
                VerticalDottedLine(
                    color = dotColor,
                    height = 14.dp
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (needsWalk) ArrivalPillBlue.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (needsWalk) Icons.AutoMirrored.Filled.DirectionsWalk else Icons.Default.Schedule,
                    contentDescription = null,
                    tint = if (needsWalk) ArrivalPillBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }
            if (hasNext) {
                VerticalDottedLine(
                    color = dotColor,
                    height = 14.dp
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JakdojadeTransitLegBlock(
    leg: JourneyLeg.TransitLeg,
    accent: Color,
    insight: TransitLegInsight?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    nextWalkOrWait: TimelineDisplayItem.WalkOrWait? = null
) {
    val s = LocalStrings.current
    val delayMins = leg.delaySeconds / 60
    val hasDelay = delayMins >= 1
    val (depStatusText, depStatusColor) = when {
        delayMins >= 1 -> s.departureDelayedBy(delayMins) to DelayRed
        delayMins <= -1 -> s.departureEarlyBy(-delayMins) to ArrivalPillBlue
        else -> s.departureOnSchedule to LiveGreen
    }

    val isLightAccent = accent.luminance() > 0.6f
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "stopsChevronRotation"
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // 1. Остановка посадки (непрерывная линия цвета сегмента уходит вниз без разрыва)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .width(74.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmallTimePill(
                    text = TimeFormatUtils.formatClock(leg.boardingStop.scheduledTimeSec),
                    bgColor = DeparturePillGreen,
                    strikethrough = hasDelay
                )
                if (hasDelay) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = TimeFormatUtils.formatClock(leg.boardingStop.effectiveTimeSec),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = DelayRed
                    )
                }
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .weight(1f)
                        .defaultMinSize(minHeight = 8.dp)
                        .background(accent)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ZoneCircleBadge(zoneId = leg.boardingStop.zoneId)
                    if (leg.boardingStop.isOnDemand) {
                        OnDemandBadge()
                    }
                    Text(
                        text = leg.boardingStop.stopName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Text(
                    text = depStatusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = depStatusColor
                )
            }
        }

        // 2. Средняя часть: значок + сплошная линия на всю высоту до остановки высадки
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .width(74.dp)
                    .fillMaxHeight(),
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
                        tint = if (isLightAccent) NightBusOnWhite else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .weight(1f)
                        .defaultMinSize(minHeight = 40.dp)
                        .background(accent)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 10.dp),
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
                            color = if (isLightAccent) accent else accent.copy(alpha = 0.16f),
                            border = BorderStroke(1.dp, accent)
                        ) {
                            Text(
                                text = leg.routeShortName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isLightAccent) NightBusOnWhite else MaterialTheme.colorScheme.onSurface,
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

                // Примерная частота отправлений и альтернативные линии (как на скриншоте Jakdojade)
                if (insight?.approxHeadwayMinutes != null) {
                    Text(
                        text = s.approxEveryMinutes(insight.approxHeadwayMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (insight != null && insight.alternativeLines.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = s.alternativesLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        insight.alternativeLines.forEach { alt ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = alt,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
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
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(chevronRotation)
                        )
                    }
                }
            }
        }

        // 2б. Плавное раскрытие/сворачивание промежуточных остановок в стиле Jakdojade (время слева, номер + зона B/C/D + ПТ + название)
        AnimatedVisibility(
            visible = expanded && leg.stops.size > 2,
            enter = expandVertically(
                animationSpec = tween(380, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top
            ) + fadeIn(tween(300)),
            exit = shrinkVertically(
                animationSpec = tween(380, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top
            ) + fadeOut(tween(240))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                leg.stops.subList(1, leg.stops.lastIndex).forEachIndexed { idx, midStop ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(74.dp)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(accent)
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Text(
                                    text = TimeFormatUtils.formatClock(midStop.effectiveTimeSec),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${idx + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ZoneCircleBadge(zoneId = midStop.zoneId)
                            if (midStop.isOnDemand) {
                                OnDemandBadge()
                            }
                            Text(
                                text = midStop.stopName,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Разделитель перед остановкой высадки с непрерывной линией слева
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(74.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .defaultMinSize(minHeight = 10.dp)
                        .background(accent)
                )
            }
            HorizontalDivider(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        // 3. Остановка высадки (пунктир к пересадке начинается сразу под капсулой времени без зазора)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .width(74.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmallTimePill(
                    text = TimeFormatUtils.formatClock(leg.alightingStop.scheduledTimeSec),
                    bgColor = ArrivalPillBlue,
                    strikethrough = hasDelay
                )
                if (hasDelay) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = TimeFormatUtils.formatClock(leg.alightingStop.effectiveTimeSec),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = DelayRed
                    )
                }
                if (nextWalkOrWait != null) {
                    val nextNeedsWalk = nextWalkOrWait.walkLeg != null && nextWalkOrWait.walkLeg.distanceMeters > 15
                    val nextDotColor = if (nextNeedsWalk) {
                        ArrivalPillBlue.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    }
                    VerticalDottedLine(
                        color = nextDotColor,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 6.dp)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = if (nextWalkOrWait != null) 4.dp else 0.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ZoneCircleBadge(zoneId = leg.alightingStop.zoneId)
                    if (leg.alightingStop.isOnDemand) {
                        OnDemandBadge()
                    }
                    Text(
                        text = leg.alightingStop.stopName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
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

/**
 * Круглый бейдж тарифной зоны (B / C / D) в стиле Jakdojade.
 * Зона A не отображается таким кружком.
 */
@Composable
private fun ZoneCircleBadge(zoneId: String) {
    val zone = zoneId.trim().uppercase()
    val bgColor = when (zone) {
        "B" -> Color(0xFF4EC28E)
        "C" -> Color(0xFF5AA8DB)
        "D" -> Color(0xFFE0A458)
        else -> return
    }
    Box(
        modifier = Modifier
            .size(21.dp)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = zone,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0E1612)
        )
    }
}

/**
 * Круглый бейдж остановки по требованию (ПТ / NŻ — Przystanek na żądanie) в стиле Jakdojade.
 */
@Composable
private fun OnDemandBadge() {
    val s = LocalStrings.current
    Box(
        modifier = Modifier
            .size(21.dp)
            .clip(CircleShape)
            .background(Color(0xFFD5D9E0)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = s.onDemandBadge,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF1A1E26),
            maxLines = 1
        )
    }
}

@Composable
private fun StopInfoPopupCard(
    popup: SelectedStopPopup,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val accent = parseHexColor(popup.colorHex)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ZoneCircleBadge(zoneId = popup.zoneId)
                    if (popup.isOnDemand) {
                        OnDemandBadge()
                    }
                    Text(
                        text = popup.stopName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                Text(
                    text = "${popup.routeName} • ${popup.timeText}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = s.close,
                    modifier = Modifier.size(16.dp)
                )
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
    fallbackFeedUpdatedMs: Long,
    getTripDelaySec: (String?) -> Int?,
    onVehicleClicked: (SelectedVehiclePopup?) -> Unit,
    onStopClicked: (SelectedStopPopup?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnVehicleClicked by rememberUpdatedState(onVehicleClicked)
    val latestOnStopClicked by rememberUpdatedState(onStopClicked)

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
    var appliedStyleJson by remember { mutableStateOf<String?>(null) }
    var clickListenerAttached by remember { mutableStateOf(false) }

    val relevantTripIds = remember(journey) {
        journey.transitLegs.map { it.tripId }.toSet()
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { mv ->
            mv.getMapAsync { map ->
                if (!clickListenerAttached) {
                    clickListenerAttached = true
                    map.addOnMapClickListener { latLng ->
                        val screenPt = map.projection.toScreenLocation(latLng)
                        val hitBox = RectF(
                            screenPt.x - 28f,
                            screenPt.y - 28f,
                            screenPt.x + 28f,
                            screenPt.y + 28f
                        )
                        val vehicleHit = map.queryRenderedFeatures(hitBox, LAYER_LIVE_VEHICLES).firstOrNull()
                        if (vehicleHit != null) {
                            val label = vehicleHit.getStringProperty("label") ?: "ZTM"
                            val isTram = vehicleHit.getBooleanProperty("isTram") ?: false
                            val delaySec = vehicleHit.getNumberProperty("delaySec")?.toInt() ?: 0
                            val updatedMs = vehicleHit.getNumberProperty("updatedMs")?.toLong()
                                ?: System.currentTimeMillis()
                            val colorHex = vehicleHit.getStringProperty("colorHex") ?: "#4B83C3"
                            latestOnVehicleClicked(
                                SelectedVehiclePopup(
                                    label = label,
                                    isTram = isTram,
                                    delaySeconds = delaySec,
                                    lastUpdatedEpochMs = updatedMs,
                                    colorHex = colorHex
                                )
                            )
                            return@addOnMapClickListener true
                        }
                        val stopHit = map.queryRenderedFeatures(hitBox, LAYER_ROUTE_STOPS).firstOrNull()
                        if (stopHit != null) {
                            val stopName = stopHit.getStringProperty("name") ?: ""
                            if (stopName.isNotBlank()) {
                                latestOnStopClicked(
                                    SelectedStopPopup(
                                        stopName = stopName,
                                        timeText = stopHit.getStringProperty("time") ?: "",
                                        routeName = stopHit.getStringProperty("routeName") ?: "",
                                        zoneId = stopHit.getStringProperty("zone") ?: "A",
                                        isOnDemand = stopHit.getBooleanProperty("isOnDemand") ?: false,
                                        colorHex = stopHit.getStringProperty("color") ?: "#3FA372"
                                    )
                                )
                                return@addOnMapClickListener true
                            }
                        }
                        latestOnVehicleClicked(null)
                        latestOnStopClicked(null)
                        false
                    }
                }

                val currentStyle = map.style
                if (currentStyle == null || !currentStyle.isFullyLoaded || appliedStyleJson != styleJson) {
                    appliedStyleJson = styleJson
                    map.setStyle(Style.Builder().fromJson(styleJson)) { loadedStyle ->
                        renderRouteOverlay(
                            style = loadedStyle,
                            journey = journey,
                            polylines = polylines,
                            outsidePolylines = outsidePolylines,
                            walkPolylines = walkPolylines,
                            liveVehicles = liveVehicles,
                            relevantTripIds = relevantTripIds,
                            fallbackFeedUpdatedMs = fallbackFeedUpdatedMs,
                            getTripDelaySec = getTripDelaySec
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
                        relevantTripIds = relevantTripIds,
                        fallbackFeedUpdatedMs = fallbackFeedUpdatedMs,
                        getTripDelaySec = getTripDelaySec
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
    relevantTripIds: Set<String>,
    fallbackFeedUpdatedMs: Long,
    getTripDelaySec: (String?) -> Int?
) {
    // 1. Полупрозрачная трасса транспорта вне нужного маршрута — цветом соответствующего сегмента
    val outsideFeatures = outsidePolylines.mapNotNull { poly ->
        if (poly.coordinates.size < 2) return@mapNotNull null
        val points = poly.coordinates.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
        Feature.fromGeometry(LineString.fromLngLats(points)).apply {
            addStringProperty("color", poly.colorHex)
        }
    }
    val outsideCollection = FeatureCollection.fromFeatures(outsideFeatures)
    val existingOutsideSource = style.getSourceAs<GeoJsonSource>(SOURCE_ROUTE_OUTSIDE)
    if (existingOutsideSource != null) {
        existingOutsideSource.setGeoJson(outsideCollection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_ROUTE_OUTSIDE, outsideCollection))
        style.addLayer(
            LineLayer(LAYER_ROUTE_OUTSIDE, SOURCE_ROUTE_OUTSIDE).withProperties(
                PropertyFactory.lineColor(Expression.get("color")),
                PropertyFactory.lineOpacity(0.32f),
                PropertyFactory.lineWidth(3.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    // 2. Активные полилинии маршрута (1-й зелёный, 2-й синий, 3-й оранжевый и т.д.)
    val lineFeatures = polylines.mapNotNull { poly ->
        if (poly.coordinates.size < 2) return@mapNotNull null
        val points = poly.coordinates.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
        Feature.fromGeometry(LineString.fromLngLats(points)).apply {
            addStringProperty("color", poly.colorHex)
        }
    }
    val routeCollection = FeatureCollection.fromFeatures(lineFeatures)
    val existingRouteSource = style.getSourceAs<GeoJsonSource>(SOURCE_ROUTE_LINES)
    if (existingRouteSource != null) {
        existingRouteSource.setGeoJson(routeCollection)
    } else {
        style.addSource(GeoJsonSource(SOURCE_ROUTE_LINES, routeCollection))
        style.addLayer(
            LineLayer(LAYER_ROUTE_LINES, SOURCE_ROUTE_LINES).withProperties(
                PropertyFactory.lineColor(Expression.get("color")),
                PropertyFactory.lineOpacity(1.0f),
                PropertyFactory.lineWidth(5.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    // 3. Пеший путь до остановки / пересадки — пунктирной линией
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
                PropertyFactory.lineColor("#38BDF8"),
                PropertyFactory.lineWidth(4.0f),
                PropertyFactory.lineDasharray(arrayOf(1.5f, 2.0f)),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    // 4. Точки остановок маршрута (обводка цветом сегмента + метаданные для клика по остановке)
    val stopFeatures = journey.transitLegs.flatMapIndexed { legIdx, leg ->
        val colorHex = RouteLegColors.hexForLeg(
            transitLegIndex = legIdx,
            shortName = leg.routeShortName,
            isTram = leg.isTram
        )
        leg.stops.map { stop ->
            Feature.fromGeometry(Point.fromLngLat(stop.longitude, stop.latitude)).apply {
                addStringProperty("color", colorHex)
                addStringProperty("name", stop.stopName)
                addStringProperty("time", TimeFormatUtils.formatClock(stop.effectiveTimeSec))
                addStringProperty("routeName", leg.routeShortName)
                addStringProperty("zone", stop.zoneId)
                addBooleanProperty("isOnDemand", stop.isOnDemand)
            }
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
                PropertyFactory.circleStrokeColor(Expression.get("color")),
                PropertyFactory.circleStrokeWidth(2.8f)
            )
        )
    }

    // 5. Живые маркеры транспорта — показываем ТОЛЬКО транспорт, едущий по текущей трассе
    val legIndexByTripId = journey.transitLegs.withIndex().associate { (idx, leg) -> leg.tripId to idx }
    val legByTripId = journey.transitLegs.associateBy { it.tripId }
    val matchingVehicles = liveVehicles.filter { it.tripId in relevantTripIds }

    val vehicleFeatures = matchingVehicles.map { v ->
        val leg = legByTripId[v.tripId]
        val legIdx = legIndexByTripId[v.tripId]
        val label = leg?.routeShortName ?: v.routeId?.take(4)?.ifBlank { "ZTM" } ?: "ZTM"
        val isTram = leg?.isTram ?: (label.toIntOrNull()?.let { it in 0..99 || it in 201..202 } ?: false)
        val colorHex = if (legIdx != null && leg != null) {
            RouteLegColors.hexForLeg(
                transitLegIndex = legIdx,
                shortName = leg.routeShortName,
                isTram = leg.isTram
            )
        } else if (!isTram && TransportMode.isNightLine(label)) {
            RouteLegColors.NIGHT_BUS_HEX
        } else {
            if (isTram) "#3FA372" else "#4B83C3"
        }
        val delaySec = getTripDelaySec(v.tripId) ?: leg?.delaySeconds ?: 0
        val updatedMs = v.timestampEpochSec?.takeIf { it > 0L }?.let { it * 1000L } ?: fallbackFeedUpdatedMs

        val iconId = "veh_circle_${if (isTram) "t" else "b"}_${colorHex.removePrefix("#")}_$label"
        if (style.getImage(iconId) == null) {
            style.addImage(
                iconId,
                createVehicleCircleBitmap(
                    label = label,
                    isTram = isTram,
                    colorHex = colorHex
                )
            )
        }
        Feature.fromGeometry(Point.fromLngLat(v.longitude, v.latitude)).apply {
            addStringProperty("icon", iconId)
            addStringProperty("label", label)
            addBooleanProperty("isTram", isTram)
            addNumberProperty("delaySec", delaySec)
            addNumberProperty("updatedMs", updatedMs)
            addStringProperty("colorHex", colorHex)
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
 * Генерирует круглый маркер транспорта со значком трамвая/автобуса и номером линии внутри кружка.
 */
private fun createVehicleCircleBitmap(
    label: String,
    isTram: Boolean,
    colorHex: String
): Bitmap {
    val size = 86
    val cx = size / 2f
    val cy = size / 2f
    val radius = size / 2f - 5f

    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val parsedBg = android.graphics.Color.parseColor(colorHex)
    val r = android.graphics.Color.red(parsedBg) / 255f
    val g = android.graphics.Color.green(parsedBg) / 255f
    val b = android.graphics.Color.blue(parsedBg) / 255f
    val isLightBg = (0.299f * r + 0.587f * g + 0.114f * b) > 0.65f
    val fgInt = if (isLightBg) android.graphics.Color.parseColor("#141820") else android.graphics.Color.WHITE
    val borderInt = if (isLightBg) android.graphics.Color.parseColor("#2A303C") else android.graphics.Color.WHITE

    // Тень + цветной круг + контрастная обводка
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = parsedBg
        style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderInt
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    canvas.drawCircle(cx, cy, radius, bgPaint)
    canvas.drawCircle(cx, cy, radius, borderPaint)

    // Значок трамвая / автобуса в верхней половине кружка
    val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fgInt
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
    }
    val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fgInt
        style = Paint.Style.FILL
    }
    val ix = cx - 11f
    val iy = 18f
    val bodyRect = RectF(ix, iy, ix + 22f, iy + 22f)
    canvas.drawRoundRect(bodyRect, 3.5f, 3.5f, iconStroke)
    canvas.drawRect(ix + 3.5f, iy + 3.5f, ix + 18.5f, iy + 12f, iconFill)
    canvas.drawCircle(ix + 5.5f, iy + 17f, 1.8f, iconFill)
    canvas.drawCircle(ix + 16.5f, iy + 17f, 1.8f, iconFill)
    if (isTram) {
        canvas.drawLine(cx, iy, cx, iy - 5f, iconStroke)
        canvas.drawLine(cx - 5f, iy - 5f, cx + 5f, iy - 5f, iconStroke)
    } else {
        canvas.drawCircle(ix + 5.5f, iy + 24.5f, 2f, iconFill)
        canvas.drawCircle(ix + 16.5f, iy + 24.5f, 2f, iconFill)
    }

    // Номер линии в нижней половине кружка
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fgInt
        textSize = if (label.length >= 3) 22f else 25f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText(label, cx, size - 15f, textPaint)

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

/**
 * Чистый, контрастный стиль векторной карты для локального `poznan.pmtiles` (Protomaps v4).
 * Разделяет дороги по иерархии (автомагистрали, главные улицы, локальные улицы, тропинки)
 * с плавным масштабированием ширины от зума и скруглёнными перекрёстками.
 */
private fun buildOfflineMapStyleJson(localMapFile: File?): String {
    if (localMapFile != null && localMapFile.exists()) {
        val uri = "pmtiles://file://${localMapFile.absolutePath}"
        return """
            {
              "version": 8,
              "name": "Poznan Offline Clean Dark",
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
                  "paint": { "background-color": "#0D1522" }
                },
                {
                  "id": "pm-earth",
                  "type": "fill",
                  "source": "poznan",
                  "source-layer": "earth",
                  "paint": { "fill-color": "#111A2B" }
                },
                {
                  "id": "pm-parks",
                  "type": "fill",
                  "source": "poznan",
                  "source-layer": "landuse",
                  "filter": ["in", "kind", "park", "forest", "wood", "grass", "meadow", "cemetery", "recreation_ground", "garden", "nature_reserve", "scrub"],
                  "paint": { "fill-color": "#132B24", "fill-opacity": 0.85 }
                },
                {
                  "id": "pm-water",
                  "type": "fill",
                  "source": "poznan",
                  "source-layer": "water",
                  "filter": ["==", ["geometry-type"], "Polygon"],
                  "paint": { "fill-color": "#173F6B" }
                },
                {
                  "id": "pm-water-lines",
                  "type": "line",
                  "source": "poznan",
                  "source-layer": "water",
                  "filter": ["==", ["geometry-type"], "LineString"],
                  "layout": { "line-cap": "round", "line-join": "round" },
                  "paint": {
                    "line-color": "#173F6B",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.0, 14, 2.5, 16, 4.5]
                  }
                },
                {
                  "id": "pm-buildings",
                  "type": "fill",
                  "source": "poznan",
                  "source-layer": "buildings",
                  "minzoom": 13,
                  "paint": {
                    "fill-color": "#1C293D",
                    "fill-outline-color": "#273750",
                    "fill-opacity": ["interpolate", ["linear"], ["zoom"], 13, 0.35, 15, 0.85]
                  }
                },
                {
                  "id": "pm-rail",
                  "type": "line",
                  "source": "poznan",
                  "source-layer": "roads",
                  "minzoom": 12,
                  "filter": ["==", "kind", "rail"],
                  "paint": {
                    "line-color": "#283548",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.8, 15, 1.6],
                    "line-opacity": 0.65
                  }
                },
                {
                  "id": "pm-paths",
                  "type": "line",
                  "source": "poznan",
                  "source-layer": "roads",
                  "minzoom": 15,
                  "filter": ["in", "kind", "path", "other"],
                  "layout": { "line-cap": "round", "line-join": "round" },
                  "paint": {
                    "line-color": "#2B3A50",
                    "line-width": ["interpolate", ["linear"], ["zoom"], 15, 1.0, 17, 2.2],
                    "line-dasharray": [2, 2],
                    "line-opacity": 0.55
                  }
                },
                {
                  "id": "pm-minor-casing",
                  "type": "line",
                  "source": "poznan",
                  "source-layer": "roads",
                  "minzoom": 12,
                  "filter": ["==", "kind", "minor_road"],
                  "layout": { "line-cap": "round", "line-join": "round" },
                  "paint": {
                    "line-color": "#0A101B",
                    "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 12, 1.8, 14, 4.8, 16, 11.0]
                  }
                },
                {
                  "id": "pm-minor-fill",
                  "type": "line",
                  "source": "poznan",
                  "source-layer": "roads",
                  "minzoom": 12,
                  "filter": ["==", "kind", "minor_road"],
                  "layout": { "line-cap": "round", "line-join": "round" },
                  "paint": {
                    "line-color": "#33445E",
                    "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 12, 1.0, 14, 3.2, 16, 8.2]
                  }
                },
                {
                  "id": "pm-major-casing",
                  "type": "line",
                  "source": "poznan",
                  "source-layer": "roads",
                  "minzoom": 9,
                  "filter": ["==", "kind", "major_road"],
                  "layout": { "line-cap": "round", "line-join": "round" },
                  "paint": {
                    "line-color": "#090F1A",
                    "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 10, 2.6, 13, 6.2, 16, 15.0]
                  }
                },
                {
                  "id": "pm-major-fill",
                  "type": "line",
                  "source": "poznan",
                  "source-layer": "roads",
                  "minzoom": 9,
                  "filter": ["==", "kind", "major_road"],
                  "layout": { "line-cap": "round", "line-join": "round" },
                  "paint": {
                    "line-color": "#526787",
                    "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 10, 1.5, 13, 4.4, 16, 12.0]
                  }
                },
                {
                  "id": "pm-highway-casing",
                  "type": "line",
                  "source": "poznan",
                  "source-layer": "roads",
                  "minzoom": 7,
                  "filter": ["==", "kind", "highway"],
                  "layout": { "line-cap": "round", "line-join": "round" },
                  "paint": {
                    "line-color": "#090F1A",
                    "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 9, 3.2, 13, 8.0, 16, 18.0]
                  }
                },
                {
                  "id": "pm-highway-fill",
                  "type": "line",
                  "source": "poznan",
                  "source-layer": "roads",
                  "minzoom": 7,
                  "filter": ["==", "kind", "highway"],
                  "layout": { "line-cap": "round", "line-join": "round" },
                  "paint": {
                    "line-color": "#6E85A8",
                    "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 9, 2.0, 13, 6.0, 16, 14.5]
                  }
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
              "paint": { "background-color": "#0D1522" }
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
