package pl.poznan.offdojade.ui.results

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.ZoneId
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import pl.poznan.offdojade.domain.model.CheapestTicketChoice
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.domain.tariff.ZtmTicketCalculator
import pl.poznan.offdojade.ui.components.AnimatedLiveSignal
import pl.poznan.offdojade.ui.components.TimeFormatUtils
import pl.poznan.offdojade.ui.localization.LocalStrings
import pl.poznan.offdojade.ui.theme.ArrivalPillBlue
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.searchActiveRequest() }) {
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
                                    null -> ""
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.hasActiveFilters) {
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
                    PullToRefreshBox(
                        isRefreshing = state.isLoadingEarlier,
                        onRefresh = viewModel::loadEarlierJourneys,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item(key = "load_earlier_header") {
                                if (state.noMoreEarlier) {
                                    Text(
                                        text = s.noEarlierRoutes,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                    )
                                } else {
                                    OutlinedButton(
                                        onClick = viewModel::loadEarlierJourneys,
                                        enabled = !state.isLoadingEarlier,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(38.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = if (state.isLoadingEarlier) s.loadingEarlier else s.loadEarlierDeparted,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }

                            items(state.journeys, key = { it.id }) { journey ->
                                MinimalJourneyCard(
                                    journey = journey,
                                    referenceNowSec = liveNowSec,
                                    onClick = {
                                        viewModel.selectJourney(journey)
                                        onOpenDetails()
                                    },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(220),
                                        placementSpec = tween(240, easing = FastOutSlowInEasing)
                                    )
                                )
                            }

                            item(key = "load_later_footer") {
                                if (state.noMoreLater) {
                                    Text(
                                        text = s.noLaterRoutes,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                    )
                                } else {
                                    OutlinedButton(
                                        onClick = viewModel::loadLaterJourneys,
                                        enabled = !state.isLoadingLater,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(38.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = if (state.isLoadingLater) s.loadingLater else s.loadLaterRoutes,
                                            style = MaterialTheme.typography.labelMedium
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val busDepSec = journey.transitDepartureTimeSec
    val isDeparted = busDepSec < referenceNowSec
    val deltaSec = busDepSec - referenceNowSec
    val deltaMins = if (isDeparted) ((-deltaSec) + 30) / 60 else deltaSec / 60
    val isOverHour = deltaMins > 60
    val isDelayed = journey.transitLegs.any { it.delaySeconds >= 60 }
    val hasRealtime = journey.transitLegs.any { it.delaySeconds != 0 }

    val ticket = journey.ticketRecommendation
    val bestPricePln = when (ticket.cheapestChoice) {
        CheapestTicketChoice.PEKA_TPORTMONETKA -> ticket.pekaCostPln
        CheapestTicketChoice.TIME_TICKET -> ticket.recommendedTimeTicketPricePln
        CheapestTicketChoice.EQUAL -> ticket.pekaCostPln
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (isDeparted) 0.58f else 1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Левая колонка: крупный обратный отсчёт (или точное время, если > 60 мин)
            Column(
                modifier = Modifier.width(96.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
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
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDeparted) OfflineGray else MaterialTheme.colorScheme.onSurface
                        )
                    } else if (!isDeparted && deltaMins == 0) {
                        Text(
                            text = "<1",
                            fontSize = 33.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 35.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = s.minShort,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    } else {
                        Text(
                            text = if (isDeparted) "-${deltaMins.coerceAtLeast(1)}" else "$deltaMins",
                            fontSize = 33.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 35.sp,
                            color = if (isDeparted) OfflineGray else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = s.minShort,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDeparted) OfflineGray else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
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

            // Центральная колонка: минималистичные значки линий + зелёная и синяя капсулы времени
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    journey.transitLegs.forEach { leg ->
                        MinimalLineChip(
                            shortName = leg.routeShortName,
                            isTram = leg.isTram
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    TimePill(
                        text = TimeFormatUtils.formatClock(busDepSec),
                        bgColor = DeparturePillGreen
                    )
                    Text(
                        text = "${journey.totalDurationMinutes} ${s.minShort}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TimePill(
                        text = TimeFormatUtils.formatClock(journey.arrivalTimeSec),
                        bgColor = ArrivalPillBlue
                    )
                }
            }

            // Правая колонка: общая длительность и минимальная цена билета
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${journey.totalDurationMinutes} ${s.minShort}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = ZtmTicketCalculator.formatPln(bestPricePln),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun MinimalLineChip(
    shortName: String,
    isTram: Boolean
) {
    val isNightBus = !isTram && TransportMode.isNightLine(shortName)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = if (isTram) Icons.Default.Tram else Icons.Default.DirectionsBus,
            contentDescription = null,
            tint = if (isNightBus) NightBusWhite else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(19.dp)
        )
        Surface(
            shape = RoundedCornerShape(7.dp),
            color = if (isNightBus) NightBusWhite else Color.Transparent,
            border = BorderStroke(
                width = 1.dp,
                color = if (isNightBus) NightBusWhite else MaterialTheme.colorScheme.outline
            )
        ) {
            Text(
                text = shortName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isNightBus) NightBusOnWhite else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
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
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
        )
    }
}
