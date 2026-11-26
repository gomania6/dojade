package pl.poznan.offdojade.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import pl.poznan.offdojade.data.local.QuickDestination
import pl.poznan.offdojade.data.local.QuickDestinationSlot
import pl.poznan.offdojade.data.local.RecentStopEntry
import pl.poznan.offdojade.data.local.TripHistoryEntry
import pl.poznan.offdojade.domain.model.AddressResult
import pl.poznan.offdojade.domain.model.NearbyStopBoard
import pl.poznan.offdojade.domain.model.RouteEndpoint
import pl.poznan.offdojade.domain.model.StopGroup
import pl.poznan.offdojade.ui.components.LineBadge
import pl.poznan.offdojade.ui.components.RealtimeStatusBadge
import pl.poznan.offdojade.ui.components.TimeFormatUtils
import pl.poznan.offdojade.ui.localization.LocalStrings
import pl.poznan.offdojade.ui.theme.ArrivalPillBlue
import pl.poznan.offdojade.ui.theme.DelayRed
import pl.poznan.offdojade.ui.theme.DeparturePillGreen
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenRouteResults: () -> Unit,
    onOpenSyncSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTimetables: () -> Unit,
    viewModel: HomeViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val realtimeStatus by viewModel.realtimeStatus.collectAsStateWithLifecycle()
    val quickDestinations by viewModel.quickDestinations.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteStopIds.collectAsStateWithLifecycle()
    val tripHistory by viewModel.tripHistory.collectAsStateWithLifecycle()
    val recentStops by viewModel.recentStops.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val s = LocalStrings.current
    val isSearchMode = state.activeField != null
    val activeQuery = when (state.activeField) {
        ActiveStopField.FROM -> state.fromQuery
        ActiveStopField.TO -> state.toQuery
        null -> ""
    }

    BackHandler(enabled = isSearchMode) {
        focusManager.clearFocus()
        viewModel.dismissSuggestions()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (isSearchMode) {
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.dismissSuggestions()
                            }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
                        }
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = s.appName,
                            fontWeight = FontWeight.Bold
                        )
                        if (!isSearchMode) {
                            RealtimeStatusBadge(status = realtimeStatus)
                        }
                    }
                },
                actions = {
                    if (!isSearchMode) {
                        IconButton(onClick = onOpenTimetables) {
                            Icon(Icons.Default.Schedule, contentDescription = s.timetables)
                        }
                        IconButton(onClick = viewModel::refreshDashboard) {
                            Icon(Icons.Default.Refresh, contentDescription = s.refresh)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = s.settings)
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Минималистичный блок поиска в стиле Jakdojade (зелёная точка -> синяя точка)
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Вертикальный коннектор: зелёная точка (Откуда) -> синяя точка (Куда)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                        .height(26.dp)
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
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Пункт А (Откуда)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (state.fromQuery.isEmpty()) {
                                            Text(
                                                text = s.myLocationGps,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        BasicTextField(
                                            value = state.fromQuery,
                                            onValueChange = viewModel::onFromQueryChange,
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.Medium
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged { focusState ->
                                                    if (focusState.isFocused) {
                                                        viewModel.onFieldFocused(ActiveStopField.FROM)
                                                    }
                                                }
                                        )
                                    }
                                    if (state.fromQuery.isNotEmpty() || state.fromEndpoint != null) {
                                        IconButton(
                                            onClick = viewModel::resetFromToGps,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = s.resetToGpsTooltip,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                // Пункт Б (Куда)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (state.toQuery.isEmpty()) {
                                            Text(
                                                text = s.chooseStopPlaceholder,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        BasicTextField(
                                            value = state.toQuery,
                                            onValueChange = viewModel::onToQueryChange,
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged { focusState ->
                                                    if (focusState.isFocused) {
                                                        viewModel.onFieldFocused(ActiveStopField.TO)
                                                    }
                                                }
                                        )
                                    }
                                    if (state.toQuery.isNotEmpty() || state.toEndpoint != null) {
                                        IconButton(
                                            onClick = viewModel::clearToStop,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = s.clear,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = viewModel::swapFromAndTo,
                                    enabled = state.fromEndpoint != null || state.toEndpoint != null
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SwapVert,
                                        contentDescription = s.swapTooltip
                                    )
                                }
                                Surface(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clickable(onClick = onOpenSettings),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = s.tripFiltersTooltip,
                                            modifier = Modifier.size(22.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        if (state.toEndpoint != null) {
                            Button(
                                onClick = {
                                    val target = state.toEndpoint ?: return@Button
                                    focusManager.clearFocus()
                                    viewModel.prepareRouteSearch(target)
                                    onOpenRouteResults()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Directions,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = s.buildRoute,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Если в режиме поиска база пуста — сразу показываем кнопку синхронизации
            if (isSearchMode && !state.graphReady) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = s.scheduleNotLoadedTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = s.scheduleNotLoadedBody,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onOpenSyncSettings) {
                                Text(s.openSync)
                            }
                        }
                    }
                }
            }

            // История остановок в поиске (показывается до ввода двух символов, исключая уже выбранную противоположную остановку)
            val excludedOppositeName = when (state.activeField) {
                ActiveStopField.FROM -> state.toEndpoint?.displayName?.trim()?.lowercase()
                ActiveStopField.TO -> state.fromEndpoint?.displayName?.trim()?.lowercase()
                null -> null
            }
            val visibleRecentStops = if (excludedOppositeName != null) {
                recentStops.filterNot { it.displayName.trim().lowercase() == excludedOppositeName }
            } else {
                recentStops
            }
            if (isSearchMode && activeQuery.trim().length < 2 && visibleRecentStops.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = s.recentStopsTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                TextButton(onClick = viewModel::clearRecentStops) {
                                    Text(s.clearHistory)
                                }
                            }
                            visibleRecentStops.forEachIndexed { idx, recent ->
                                val asGroup = StopGroup(
                                    displayName = recent.displayName,
                                    primaryStopId = recent.stopId,
                                    stopIds = listOf(recent.stopId),
                                    zones = recent.zones,
                                    platformCount = recent.platformCount,
                                    latitude = recent.latitude,
                                    longitude = recent.longitude
                                )
                                RecentStopSearchRow(
                                    entry = recent,
                                    onClick = {
                                        focusManager.clearFocus()
                                        when (state.activeField) {
                                            ActiveStopField.FROM -> viewModel.selectFromStop(asGroup)
                                            ActiveStopField.TO -> {
                                                if (viewModel.selectToStop(asGroup)) {
                                                    viewModel.prepareRouteSearch(RouteEndpoint.Stop(asGroup))
                                                    onOpenRouteResults()
                                                }
                                            }
                                            null -> Unit
                                        }
                                    }
                                )
                                if (idx < visibleRecentStops.lastIndex) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Подсказки автодополнения: остановки (с учётом польских букв) + адреса
            if (isSearchMode && (state.searchResults.isNotEmpty() || state.addressResults.isNotEmpty())) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column {
                            state.searchResults.forEachIndexed { idx, group ->
                                StopGroupSearchRow(
                                    group = group,
                                    isFavorite = group.primaryStopId in favoriteIds,
                                    onClick = {
                                        focusManager.clearFocus()
                                        when (state.activeField) {
                                            ActiveStopField.FROM -> viewModel.selectFromStop(group)
                                            ActiveStopField.TO -> {
                                                if (viewModel.selectToStop(group)) {
                                                    viewModel.prepareRouteSearch(RouteEndpoint.Stop(group))
                                                    onOpenRouteResults()
                                                }
                                            }
                                            null -> Unit
                                        }
                                    },
                                    onToggleFavorite = { viewModel.toggleFavoriteStop(group.primaryStopId) }
                                )
                                if (idx < state.searchResults.lastIndex || state.addressResults.isNotEmpty()) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                                }
                            }

                            state.addressResults.forEachIndexed { idx, address ->
                                AddressSearchRow(
                                    address = address,
                                    onClick = {
                                        focusManager.clearFocus()
                                        when (state.activeField) {
                                            ActiveStopField.FROM -> viewModel.selectFromAddress(address)
                                            ActiveStopField.TO -> {
                                                viewModel.selectToAddress(address)
                                                viewModel.prepareRouteSearch(RouteEndpoint.Address(address))
                                                onOpenRouteResults()
                                            }
                                            null -> Unit
                                        }
                                    }
                                )
                                if (idx < state.addressResults.lastIndex) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                                }
                            }
                        }
                    }
                }
            }

            // В режиме поиска убираем всё лишнее с главного экрана — оставляем только поиск и подсказки
            if (!isSearchMode) {
                // Быстрый доступ к статическим расписаниям и настройкам поездки
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenTimetables,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.timetables, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(s.settings, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // 2. 3 быстрые карточки: «Домой», «В центр», «На работу»
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = s.quickRoutesTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = s.quickRoutesHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            quickDestinations.forEach { dest ->
                                val localizedTitle = when (dest.slot) {
                                    QuickDestinationSlot.HOME -> s.quickHome
                                    QuickDestinationSlot.CENTER -> s.quickCenter
                                    QuickDestinationSlot.WORK -> s.quickWork
                                }
                                QuickDestinationCard(
                                    destination = dest.copy(title = localizedTitle),
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        scope.launch {
                                            val resolved = viewModel.resolveQuickDestinationStop(dest)
                                            if (resolved != null) {
                                                viewModel.prepareRouteSearchToStopId(resolved.first, resolved.second)
                                                onOpenRouteResults()
                                            } else {
                                                viewModel.openQuickDestinationEditor(dest.slot)
                                            }
                                        }
                                    },
                                    onLongClick = { viewModel.openQuickDestinationEditor(dest.slot) }
                                )
                            }
                        }
                    }
                }

                // 2.5. История поездок
                if (tripHistory.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = s.tripHistoryTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(onClick = viewModel::clearTripHistory) {
                                    Text(s.clearHistory)
                                }
                            }
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Column {
                                    tripHistory.forEachIndexed { idx, item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    viewModel.prepareRouteFromHistory(item)
                                                    onOpenRouteResults()
                                                }
                                                .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.History,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                text = "${item.fromName ?: s.myLocation} → ${item.toName}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(onClick = { viewModel.removeTripHistory(item.id) }) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = s.clear,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        if (idx < tripHistory.lastIndex) {
                                            HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Ближайшие остановки рядом
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = s.nearbyStopsTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (state.userLocation?.isFallbackCenter == true) {
                            Text(
                                text = s.poznanCenter,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (!state.graphReady) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = s.scheduleNotLoadedTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = s.scheduleNotLoadedBody,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(onClick = onOpenSyncSettings) {
                                    Text(s.openSync)
                                }
                            }
                        }
                    }
                } else if (state.nearbyBoards.isEmpty()) {
                    item {
                        Text(
                            text = s.searchingNearby,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(state.nearbyBoards, key = { it.stopId }) { board ->
                        NearbyStopCard(
                            board = board,
                            onClick = {
                                viewModel.prepareRouteSearchToStopId(board.stopId, board.stopName)
                                onOpenRouteResults()
                            }
                        )
                    }
                }
            }
        }
    }

    // Диалог выбора остановки для быстрой карточки
    val editingSlot = state.editingSlot
    if (editingSlot != null) {
        val slotTitle = when (editingSlot) {
            QuickDestinationSlot.HOME -> s.quickHome
            QuickDestinationSlot.CENTER -> s.quickCenter
            QuickDestinationSlot.WORK -> s.quickWork
        }
        AlertDialog(
            onDismissRequest = viewModel::closeQuickDestinationEditor,
            title = { Text(s.stopForSlotTitle(slotTitle)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.slotSearchQuery,
                        onValueChange = viewModel::onSlotSearchQueryChange,
                        placeholder = { Text(s.searchStopPlaceholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(modifier = Modifier.height(260.dp)) {
                        items(state.slotSearchResults, key = { it.primaryStopId }) { group ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.assignQuickDestination(editingSlot, group) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(group.displayName, fontWeight = FontWeight.Medium)
                                    val zonesText = "${s.zonePrefix} ${group.zones.joinToString("/").ifEmpty { "A" }}"
                                    val platformsText = if (group.platformCount > 1) " • ${group.platformCount} ${s.platformsShort}" else ""
                                    Text(
                                        text = "$zonesText$platformsText",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::closeQuickDestinationEditor) {
                    Text(s.close)
                }
            }
        )
    }
}

@Composable
private fun RecentStopSearchRow(
    entry: RecentStopEntry,
    onClick: () -> Unit
) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            val zonesText = "${s.zonePrefix} ${entry.zones.joinToString("/").ifEmpty { "A" }}"
            val platformSuffix = if (entry.platformCount > 1) s.mergedPlatformsSuffix(entry.platformCount) else ""
            Text(
                text = "$zonesText$platformSuffix",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AddressSearchRow(
    address: AddressResult,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = address.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = address.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickDestinationCard(
    destination: QuickDestination,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val icon = when (destination.slot) {
        QuickDestinationSlot.HOME -> Icons.Default.Home
        QuickDestinationSlot.CENTER -> Icons.Default.LocationCity
        QuickDestinationSlot.WORK -> Icons.Default.Work
    }

    Card(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = destination.title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = destination.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = destination.stopName ?: s.setStopPlaceholder,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StopGroupSearchRow(
    group: StopGroup,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val s = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            val zonesText = "${s.zonePrefix} ${group.zones.joinToString("/").ifEmpty { "A" }}"
            val platformSuffix = if (group.platformCount > 1) s.mergedPlatformsSuffix(group.platformCount) else ""
            Text(
                text = "$zonesText$platformSuffix",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = s.favoriteTooltip,
                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NearbyStopCard(
    board: NearbyStopBoard,
    onClick: () -> Unit
) {
    val s = LocalStrings.current
    val nowSec = LocalTime.now().toSecondOfDay()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = board.stopName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val platformsInfo = if (board.platformCount > 1) " • ${board.platformCount} ${s.platformsShort}" else ""
                    Text(
                        text = "${s.walkApprox(board.distanceMeters, (board.walkTimeSeconds + 30) / 60)} • ${s.zonePrefix} ${board.zoneId}$platformsInfo",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (board.departures.isEmpty()) {
                Text(
                    text = s.noUpcomingDepartures,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                board.departures.forEach { dep ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LineBadge(
                            shortName = dep.routeShortName,
                            isTram = dep.routeType == 0,
                            colorHex = dep.routeColorHex
                        )
                        Text(
                            text = dep.headsign.ifBlank { s.onSchedule },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = TimeFormatUtils.formatRelativeDeparture(dep.effectiveTimeSec, nowSec, s),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            val delayText = TimeFormatUtils.formatDelayBadge(dep.delaySeconds, s)
                            if (delayText != null) {
                                Text(
                                    text = delayText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (dep.delaySeconds > 0) DelayRed else MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = TimeFormatUtils.formatClock(dep.effectiveTimeSec),
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
}
