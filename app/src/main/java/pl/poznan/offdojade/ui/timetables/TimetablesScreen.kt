package pl.poznan.offdojade.ui.timetables

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import pl.poznan.offdojade.data.local.entity.RouteEntity
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.domain.routing.StaticDepartureEntry
import pl.poznan.offdojade.domain.routing.StaticStopTimetable
import pl.poznan.offdojade.domain.routing.TimetableDayType
import pl.poznan.offdojade.ui.components.LineBadge
import pl.poznan.offdojade.ui.components.TimetableStopsMapView
import pl.poznan.offdojade.ui.localization.LocalStrings
import pl.poznan.offdojade.ui.theme.NightBusOnWhite
import pl.poznan.offdojade.ui.theme.NightBusWhite
import java.io.File
import java.time.LocalTime
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimetablesScreen(
    onBack: () -> Unit,
    onOpenSync: () -> Unit,
    showBackButton: Boolean = true,
    viewModel: TimetablesViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val s = LocalStrings.current

    val isNested = state.selectedStop != null || state.selectedRoute != null
    val handleBack: () -> Unit = {
        when {
            state.selectedStop != null -> viewModel.clearSelectedStop()
            state.selectedRoute != null -> viewModel.clearSelectedRoute()
            else -> onBack()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showBackButton || isNested) {
                        IconButton(onClick = handleBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
                        }
                    }
                },
                title = {
                    val titleText = when {
                        state.selectedStop != null -> state.selectedStop!!.stopName
                        state.selectedRoute != null -> "${s.timetables}: ${state.selectedRoute!!.shortName}"
                        else -> s.timetablesTitle
                    }
                    Text(
                        text = titleText,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    ) { padding ->
        val viewLevel = when {
            state.selectedStop != null && state.selectedRoute != null -> 2
            state.selectedRoute != null -> 1
            else -> 0
        }
        AnimatedContent(
            targetState = viewLevel,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(tween(240)) { it / 6 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(200)) { -it / 10 } + fadeOut(tween(160)))
                } else {
                    (slideInHorizontally(tween(240)) { -it / 6 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(200)) { it / 10 } + fadeOut(tween(160)))
                }
            },
            label = "timetablesLevelTransition",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { level ->
            when (level) {
                2 -> {
                    val route = state.selectedRoute
                    val stop = state.selectedStop
                    if (route != null && stop != null) {
                        StopTimetableGrid(
                            route = route,
                            stop = stop,
                            dayType = state.selectedDayType,
                            onSelectDayType = viewModel::selectDayType,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                1 -> {
                    if (state.selectedRoute != null) {
                        RouteStopsView(
                            state = state,
                            localMapFile = remember { viewModel.getLocalMapFile() },
                            onSelectDayType = viewModel::selectDayType,
                            onSelectDirection = viewModel::selectDirection,
                            onSelectStop = viewModel::selectStop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                else -> {
                    LinesBrowserView(
                        state = state,
                        onSearchChange = viewModel::onSearchQueryChange,
                        onCategoryChange = viewModel::onCategoryChange,
                        onSelectRoute = viewModel::selectRoute,
                        onOpenSync = onOpenSync,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun LinesBrowserView(
    state: TimetablesUiState,
    onSearchChange: (String) -> Unit,
    onCategoryChange: (LineCategoryFilter) -> Unit,
    onSelectRoute: (RouteEntity) -> Unit,
    onOpenSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current

    Column(modifier = modifier) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text(s.searchLinePlaceholder) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = s.clear)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Вкладки «Трамваи» / «Автобусы» как в Jakdojade
        val selectedTab = if (state.categoryFilter == LineCategoryFilter.TRAMS) 0 else 1
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { onCategoryChange(LineCategoryFilter.TRAMS) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Tram, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(s.filterTrams, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { onCategoryChange(LineCategoryFilter.BUSES) },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.DirectionsBus, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(s.filterBuses, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }

        when {
            state.isLoadingRoutes -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.allRoutes.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = s.scheduleNotLoadedTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = s.scheduleNotLoadedBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onOpenSync) {
                                Text(s.openSync)
                            }
                        }
                    }
                }
            }

            state.filteredRoutes.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = s.noLinesFound,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                // Компактная сетка номеров линий (6 в ряд) со стройными плашками как в поиске маршрутов
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.filteredRoutes, key = { it.routeId }) { route ->
                        val isNightBus = TransportMode.isNightLine(route.shortName)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .clickable { onSelectRoute(route) },
                            shape = RoundedCornerShape(7.dp),
                            color = if (isNightBus) NightBusWhite else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isNightBus) {
                                    NightBusWhite
                                } else {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                }
                            )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = route.shortName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isNightBus) NightBusOnWhite else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1
                                )
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
private fun RouteStopsView(
    state: TimetablesUiState,
    localMapFile: File?,
    onSelectDayType: (TimetableDayType) -> Unit,
    onSelectDirection: (Int) -> Unit,
    onSelectStop: (StaticStopTimetable) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var showMapView by remember(state.selectedRoute?.routeId) { mutableStateOf(false) }

    Column(modifier = modifier) {
        DayTypeSelectorRow(
            selected = state.selectedDayType,
            onSelect = onSelectDayType,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (state.isLoadingTimetable) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        if (state.directions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = s.noDeparturesThisDay,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        if (state.directions.size > 1) {
            TabRow(selectedTabIndex = state.selectedDirectionIdx.coerceIn(0, state.directions.lastIndex)) {
                state.directions.forEachIndexed { index, dir ->
                    Tab(
                        selected = state.selectedDirectionIdx == index,
                        onClick = { onSelectDirection(index) },
                        text = {
                            Text(
                                text = "→ ${dir.headsign}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        } else {
            Text(
                text = "${s.directionLabel}: → ${state.directions.first().headsign}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Переключатель «Список | Карта»
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !showMapView,
                onClick = { showMapView = false },
                label = { Text(s.stopsListTab) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.FormatListBulleted,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
            FilterChip(
                selected = showMapView,
                onClick = { showMapView = true },
                label = { Text(s.stopsMapTab) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }

        val currentDir = state.currentDirection ?: return
        if (showMapView) {
            val isTram = state.selectedRoute?.routeType == 0
            val lineColorHex = if (isTram) "#3FA372" else "#4B83C3"
            TimetableStopsMapView(
                stops = currentDir.stops,
                lineColorHex = lineColorHex,
                localMapFile = localMapFile,
                onSelectStop = onSelectStop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(currentDir.stops, key = { it.stopId }) { stop ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !stop.isTerminal) { onSelectStop(stop) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${s.zonePrefix} ${stop.zoneId}" +
                                        if (stop.platformCode.isNotBlank()) " • ${s.platformPrefix} ${stop.platformCode}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!stop.isTerminal) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
private fun StopTimetableGrid(
    route: RouteEntity,
    stop: StaticStopTimetable,
    dayType: TimetableDayType,
    onSelectDayType: (TimetableDayType) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val nowSec = remember { LocalTime.now().toSecondOfDay() }

    // Группируем отправления по часу (0..27 -> отображаем % 24)
    val byHour = remember(stop.departures) {
        stop.departures.groupBy { (it.departureTimeSec / 3600) % 24 }
            .entries
            .sortedBy { if (it.key < 4) it.key + 24 else it.key }
    }
    val nextDepartureSec = remember(stop.departures, nowSec, dayType) {
        if (dayType == TimetableDayType.TODAY) {
            stop.departures.firstOrNull { it.departureTimeSec >= nowSec }?.departureTimeSec
        } else {
            null
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LineBadge(
                shortName = route.shortName,
                isTram = route.routeType == 0,
                colorHex = route.routeColor,
                textColorHex = route.routeTextColor
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = s.departuresForStop(stop.stopName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${s.zonePrefix} ${stop.zoneId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DayTypeSelectorRow(
            selected = dayType,
            onSelect = onSelectDayType,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (byHour.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = s.noDeparturesThisDay,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = s.hourColumn,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(52.dp)
                    )
                    Text(
                        text = s.minutesColumn,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
            }

            items(byHour, key = { it.key }) { (hour, entries) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = String.format(Locale.US, "%02d", hour),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(44.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        entries.forEach { entry ->
                            MinuteBadge(
                                entry = entry,
                                isNext = entry.departureTimeSec == nextDepartureSec
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MinuteBadge(
    entry: StaticDepartureEntry,
    isNext: Boolean
) {
    val minute = (entry.departureTimeSec % 3600) / 60
    val label = String.format(Locale.US, "%02d", minute)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isNext) FontWeight.ExtraBold else FontWeight.Medium,
            textDecoration = if (entry.isLowFloor) TextDecoration.Underline else TextDecoration.None,
            color = if (isNext) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayTypeSelectorRow(
    selected: TimetableDayType,
    onSelect: (TimetableDayType) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(
            TimetableDayType.TODAY to s.dayToday,
            TimetableDayType.WEEKDAY to s.dayWeekday,
            TimetableDayType.SATURDAY to s.daySaturday,
            TimetableDayType.SUNDAY to s.daySunday
        ).forEach { (type, label) ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(label) }
            )
        }
    }
}
