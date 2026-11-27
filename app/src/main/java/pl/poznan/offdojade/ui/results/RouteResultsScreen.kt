package pl.poznan.offdojade.ui.results

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import pl.poznan.offdojade.data.realtime.RealtimeStatus
import pl.poznan.offdojade.domain.model.CheapestTicketChoice
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.domain.tariff.ZtmTicketCalculator
import pl.poznan.offdojade.ui.components.AnimatedLiveSignal
import pl.poznan.offdojade.ui.components.TimeFormatUtils
import pl.poznan.offdojade.ui.localization.LocalStrings
import pl.poznan.offdojade.ui.theme.ArrivalPillBlue
import pl.poznan.offdojade.ui.theme.DelayAmber
import pl.poznan.offdojade.ui.theme.DeparturePillGreen
import pl.poznan.offdojade.ui.theme.LiveGreen
import pl.poznan.offdojade.ui.theme.NightBusOnWhite
import pl.poznan.offdojade.ui.theme.NightBusWhite
import pl.poznan.offdojade.ui.theme.OfflineGray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteResultsScreen(
    onBack: () -> Unit,
    onOpenDetails: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: RouteResultsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val realtimeStatus by viewModel.realtimeStatus.collectAsStateWithLifecycle()
    val liveTripDelays by viewModel.liveTripDelays.collectAsStateWithLifecycle()
    var offlineBannerDismissed by rememberSaveable { mutableStateOf(false) }
    val s = LocalStrings.current

    // Автоматическое обновление текущего времени каждые 15 секунд во время просмотра маршрутов
    var liveNowSec by remember {
        mutableIntStateOf(LocalTime.now(ZoneId.of("Europe/Warsaw")).toSecondOfDay())
    }
    LaunchedEffect(Unit) {
        viewModel.searchActiveRequest()
        while (true) {
            delay(15_000L)
            liveNowSec = LocalTime.now(ZoneId.of("Europe/Warsaw")).toSecondOfDay()
        }
    }

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
                        if (!state.isLoading && state.journeys.isNotEmpty()) {
                            Text(
                                text = s.foundVariantsMeta(state.journeys.size, state.searchDurationMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.searchActiveRequest(forceRefresh = true) }) {
                            Icon(Icons.Default.Refresh, contentDescription = s.recalculate)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Box {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = s.tripFiltersTooltip,
                                    tint = if (state.hasActiveFilters) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                if (state.hasActiveFilters) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .align(Alignment.TopEnd)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error)
                                    )
                                }
                            }
                        }
                    }
                )

                // Минималистичная шапка маршрута Откуда -> Куда (нажатие возвращает к редактированию поиска)
                val fromLabel = state.fromStopName ?: s.myLocation
                val toLabel = state.targetStopName.ifBlank { s.routeSearchTitle }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onBack)
                        .padding(start = 18.dp, end = 16.dp, top = 2.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(DeparturePillGreen)
                        )
                        Box(
                            modifier = Modifier
                                .width(2.5.dp)
                                .height(18.dp)
                                .background(MaterialTheme.colorScheme.outline)
                        )
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(ArrivalPillBlue)
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = fromLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = toLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AnimatedVisibility(
                    visible = realtimeStatus is RealtimeStatus.Offline && !offlineBannerDismissed,
                    enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
                ) {
                    Surface(
                        color = DelayAmber.copy(alpha = 0.13f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = DelayAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = s.offlineNoticeBanner,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { offlineBannerDismissed = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = s.close,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        val contentState = when {
            state.isLoading -> 0
            state.error != null -> 1
            else -> 2
        }
        AnimatedContent(
            targetState = contentState,
            transitionSpec = {
                fadeIn(tween(220, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(160, easing = FastOutSlowInEasing))
            },
            label = "resultsStateTransition",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { target ->
            when (target) {
                0 -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                1 -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = when (state.error) {
                                    RouteSearchError.SCHEDULE_NOT_LOADED -> s.scheduleNotLoadedError
                                    RouteSearchError.NO_ROUTES_FOUND -> s.noRoutesFound
                                    RouteSearchError.TOO_CLOSE_WALK -> s.tooCloseWalkHint(
                                        state.directWalkDistanceMeters,
                                        state.directWalkMinutes
                                    )
                                    null -> ""
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            if (state.hasActiveFilters && state.error != RouteSearchError.TOO_CLOSE_WALK) {
                                OutlinedButton(onClick = onOpenSettings) {
                                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(s.tripFiltersTooltip)
                                }
                            }
                        }
                    }
                }

                else -> {
                    val latestCanLoadEarlier by rememberUpdatedState(
                        !state.isLoadingEarlier && !state.noMoreEarlier && state.journeys.isNotEmpty()
                    )
                    val latestCanLoadLater by rememberUpdatedState(
                        !state.isLoadingLater && !state.noMoreLater && state.journeys.isNotEmpty()
                    )
                    val density = LocalDensity.current
                    val overscrollThresholdPx = remember(density) { with(density) { 48.dp.toPx() } }
                    var topOverscrollPx by remember { mutableFloatStateOf(0f) }
                    var bottomOverscrollPx by remember { mutableFloatStateOf(0f) }
                    val swipePaginationNestedScroll = remember(overscrollThresholdPx) {
                        object : NestedScrollConnection {
                            override fun onPostScroll(
                                consumed: Offset,
                                available: Offset,
                                source: NestedScrollSource
                            ): Offset {
                                if (source == NestedScrollSource.UserInput) {
                                    when {
                                        available.y > 0f && latestCanLoadEarlier -> {
                                            bottomOverscrollPx = 0f
                                            topOverscrollPx += available.y
                                            if (topOverscrollPx >= overscrollThresholdPx) {
                                                topOverscrollPx = 0f
                                                viewModel.loadEarlierJourneys()
                                            }
                                        }
                                        available.y < 0f && latestCanLoadLater -> {
                                            topOverscrollPx = 0f
                                            bottomOverscrollPx += -available.y
                                            if (bottomOverscrollPx >= overscrollThresholdPx) {
                                                bottomOverscrollPx = 0f
                                                viewModel.loadLaterJourneys()
                                            }
                                        }
                                        else -> {
                                            topOverscrollPx = 0f
                                            bottomOverscrollPx = 0f
                                        }
                                    }
                                }
                                return Offset.Zero
                            }

                            override suspend fun onPreFling(available: Velocity): Velocity {
                                topOverscrollPx = 0f
                                bottomOverscrollPx = 0f
                                return Velocity.Zero
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(swipePaginationNestedScroll),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = "load_earlier_header") {
                                if (state.noMoreEarlier) {
                                    Text(
                                        text = s.noEarlierRoutes,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    )
                                } else {
                                    OutlinedButton(
                                        onClick = viewModel::loadEarlierJourneys,
                                        enabled = !state.isLoadingEarlier,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 40.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = if (state.isLoadingEarlier) s.loadingEarlier else s.loadEarlierDeparted,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            val todayDate = LocalDate.now(ZoneId.of("Europe/Warsaw"))
                            itemsIndexed(state.journeys, key = { _, j -> j.id }) { idx, journey ->
                                val dayOffset = (journey.transitDepartureTimeSec / 86_400).coerceAtLeast(0)
                                val prevDayOffset = if (idx > 0) {
                                    (state.journeys[idx - 1].transitDepartureTimeSec / 86_400).coerceAtLeast(0)
                                } else {
                                    0
                                }
                                val showDayDivider = (idx == 0 && dayOffset > 0) || (idx > 0 && dayOffset != prevDayOffset)

                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(220),
                                        placementSpec = tween(240, easing = FastOutSlowInEasing)
                                    )
                                ) {
                                    if (showDayDivider) {
                                        val date = todayDate.plusDays(dayOffset.toLong())
                                        val prefix = when (dayOffset) {
                                            1 -> "${s.tomorrowLabel}, "
                                            2 -> "${s.dayAfterTomorrowLabel}, "
                                            else -> ""
                                        }
                                        val formatted = date.format(
                                            DateTimeFormatter.ofPattern("EEE d MMM", Locale.forLanguageTag(s.localeTag))
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            HorizontalDivider(
                                                modifier = Modifier.weight(1f),
                                                color = MaterialTheme.colorScheme.outlineVariant
                                            )
                                            Text(
                                                text = "$prefix$formatted",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            HorizontalDivider(
                                                modifier = Modifier.weight(1f),
                                                color = MaterialTheme.colorScheme.outlineVariant
                                            )
                                        }
                                    }

                                    MinimalJourneyCard(
                                        journey = journey,
                                        referenceNowSec = liveNowSec,
                                        liveTripDelays = liveTripDelays,
                                        onClick = {
                                            viewModel.selectJourney(journey)
                                            onOpenDetails()
                                        }
                                    )
                                }
                            }

                            item(key = "load_later_footer") {
                                if (state.noMoreLater) {
                                    Text(
                                        text = s.noLaterRoutes,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    )
                                } else {
                                    OutlinedButton(
                                        onClick = viewModel::loadLaterJourneys,
                                        enabled = !state.isLoadingLater,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 40.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = if (state.isLoadingLater) s.loadingLater else s.loadLaterRoutes,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MinimalJourneyCard(
    journey: TransitJourney,
    referenceNowSec: Int,
    liveTripDelays: Map<String, Int> = emptyMap(),
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val busDepSec = journey.transitDepartureTimeSec
    val isDeparted = busDepSec < referenceNowSec
    val deltaSec = busDepSec - referenceNowSec
    val deltaMins = if (isDeparted) ((-deltaSec) + 30) / 60 else deltaSec / 60
    val isOverHour = deltaMins > 60
    val hasRealtime = !journey.isFutureDay && journey.transitLegs.any { leg ->
        val core = leg.tripId.substringAfter('_', "")
        leg.tripId in liveTripDelays || (core.isNotEmpty() && core in liveTripDelays) || leg.delaySeconds != 0
    }
    val isDelayed = !journey.isFutureDay && journey.transitLegs.any { leg ->
        val core = leg.tripId.substringAfter('_', "")
        val d = liveTripDelays[leg.tripId]
            ?: (if (core.isNotEmpty()) liveTripDelays[core] else null)
            ?: leg.delaySeconds
        kotlin.math.abs(d) >= 60
    }
    val isDepartingNow = !isDeparted && !isOverHour && deltaMins == 0
    val departingNowColor = if (isDepartingNow) {
        val pulse = rememberInfiniteTransition(label = "cardDepartPulse")
        val animated by pulse.animateColor(
            initialValue = MaterialTheme.colorScheme.onSurface,
            targetValue = OfflineGray,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cardDepartPulseColor"
        )
        animated
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val ticket = journey.ticketRecommendation
    val bestPricePln = when (ticket.cheapestChoice) {
        CheapestTicketChoice.PEKA_TPORTMONETKA -> ticket.pekaCostPln
        CheapestTicketChoice.TIME_TICKET -> ticket.recommendedTimeTicketPricePln
        CheapestTicketChoice.EQUAL -> ticket.pekaCostPln
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isDeparted) 0.58f else 1f),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Левая колонка: крупные цифры и подпись «мин» ровно по нижней базовой линии цифр
            Column(
                modifier = Modifier.widthIn(min = 90.dp, max = 114.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (isDeparted) s.departedLabel else if (isOverHour) s.onSchedule else s.departingCaption,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!isDeparted && hasRealtime) {
                        AnimatedLiveSignal(isDelayed = isDelayed)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (isOverHour) {
                        Text(
                            text = TimeFormatUtils.formatClock(busDepSec),
                            fontSize = 27.sp,
                            lineHeight = 29.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDeparted) OfflineGray else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false
                        )
                    } else if (!isDeparted && deltaMins == 0) {
                        Text(
                            text = "<1",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 36.sp,
                            color = departingNowColor,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.alignByBaseline()
                        )
                        Text(
                            text = s.minShort,
                            fontSize = 12.5.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.alignByBaseline()
                        )
                    } else {
                        Text(
                            text = if (isDeparted) "-${deltaMins.coerceAtLeast(1)}" else "$deltaMins",
                            fontSize = if (isDeparted) 31.sp else 34.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 36.sp,
                            color = if (isDeparted) OfflineGray else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.alignByBaseline()
                        )
                        Text(
                            text = s.minShort,
                            fontSize = 12.5.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDeparted) OfflineGray else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.alignByBaseline()
                        )
                    }
                }
            }

            // Правая область:
            // 1-я строка: бейджи линий (FlowRow — не обрезаются при 3+ пересадках) + общее время справа
            // 2-я строка: 🚶 X мин (отпр) длит (приб) слева + цена справа (без выталкивания цены)
            val isCompactLegs = journey.transitLegs.size >= 3
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        journey.transitLegs.forEach { leg ->
                            MinimalLineChip(
                                shortName = leg.routeShortName,
                                isTram = leg.isTram,
                                isRail = leg.isRail,
                                compact = isCompactLegs,
                                showIcon = leg.isRail
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = TimeFormatUtils.formatDuration(journey.totalDurationMinutes, s),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                val firstWalkLeg = journey.legs.firstOrNull() as? JourneyLeg.WalkLeg
                val initialWalkMins = if (firstWalkLeg != null && firstWalkLeg.distanceMeters > 15) {
                    ((firstWalkLeg.durationSec + 30) / 60).coerceAtLeast(1)
                } else {
                    0
                }
                val transitDurationMins = ((journey.arrivalTimeSec - busDepSec + 30) / 60).coerceAtLeast(1)
                val compactTransitDur = if (transitDurationMins >= 60) {
                    val h = transitDurationMins / 60
                    val m = transitDurationMins % 60
                    if (m == 0) "$h${s.hourShort}" else "$h${s.hourShort} $m${s.minShort.take(1)}"
                } else {
                    "$transitDurationMins ${s.minShort}"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (initialWalkMins > 0) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = "$initialWalkMins ${s.minShort}",
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        TimePill(
                            text = TimeFormatUtils.formatClock(busDepSec),
                            bgColor = DeparturePillGreen
                        )
                        Text(
                            text = compactTransitDur,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        TimePill(
                            text = TimeFormatUtils.formatClock(journey.arrivalTimeSec),
                            bgColor = ArrivalPillBlue
                        )
                    }
                    if (!ticket.hasNonZtmPksLeg && !ticket.hasRailLeg) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = ZtmTicketCalculator.formatPln(bestPricePln),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MinimalLineChip(
    shortName: String,
    isTram: Boolean,
    isRail: Boolean = false,
    compact: Boolean = false,
    showIcon: Boolean = true
) {
    val isNightLine = TransportMode.isNightLine(shortName)
    val railAccent = Color(0xFFD32F2F)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)
    ) {
        if (showIcon) {
            Icon(
                imageVector = when {
                    isRail -> Icons.Default.Train
                    isTram -> Icons.Default.Tram
                    else -> Icons.Default.DirectionsBus
                },
                contentDescription = null,
                tint = when {
                    isRail -> railAccent
                    isNightLine -> NightBusWhite
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(if (compact) 14.dp else 16.dp)
            )
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = when {
                isRail -> railAccent.copy(alpha = 0.14f)
                isNightLine -> NightBusWhite
                else -> Color.Transparent
            },
            border = BorderStroke(
                width = 1.dp,
                color = when {
                    isRail -> railAccent
                    isNightLine -> NightBusWhite
                    else -> MaterialTheme.colorScheme.outline
                }
            )
        ) {
            Text(
                text = shortName,
                fontSize = if (compact) 12.5.sp else 14.sp,
                lineHeight = if (compact) 15.sp else 17.sp,
                fontWeight = FontWeight.Bold,
                color = if (isNightLine) NightBusOnWhite else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(
                    horizontal = if (compact) 5.dp else 7.dp,
                    vertical = if (compact) 1.5.dp else 2.dp
                )
            )
        }
    }
}

@Composable
private fun TimePill(
    text: String,
    bgColor: Color
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = bgColor
    ) {
        Text(
            text = text,
            fontSize = 10.5.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 5.5.dp, vertical = 2.dp)
        )
    }
}
