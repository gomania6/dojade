package pl.poznan.offdojade.ui.home

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.local.QuickDestination
import pl.poznan.offdojade.data.local.QuickDestinationSlot
import pl.poznan.offdojade.data.local.RecentStopEntry
import pl.poznan.offdojade.domain.model.AddressResult
import pl.poznan.offdojade.domain.model.NearbyStopBoard
import pl.poznan.offdojade.domain.model.PlannedTime
import pl.poznan.offdojade.domain.model.RouteEndpoint
import pl.poznan.offdojade.domain.model.StopGroup
import pl.poznan.offdojade.domain.model.TimeQueryMode
import pl.poznan.offdojade.ui.components.LineBadge
import pl.poznan.offdojade.ui.components.MapLocationPickerDialog
import pl.poznan.offdojade.ui.components.TimeFormatUtils
import pl.poznan.offdojade.ui.localization.LocalStrings
import pl.poznan.offdojade.ui.theme.ArrivalPillBlue
import pl.poznan.offdojade.ui.theme.DelayRed
import pl.poznan.offdojade.ui.theme.DeparturePillGreen
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    onOpenRouteResults: () -> Unit,
    onOpenSyncSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val quickDestinations by viewModel.quickDestinations.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteStopIds.collectAsStateWithLifecycle()
    val tripHistory by viewModel.tripHistory.collectAsStateWithLifecycle()
    val recentStops by viewModel.recentStops.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val s = LocalStrings.current

    var pendingQuickDest by remember { mutableStateOf<QuickDestination?>(null) }

    fun launchQuickRouteWithGps(dest: QuickDestination) {
        scope.launch {
            val resolved = viewModel.resolveQuickDestinationStop(dest)
            if (resolved != null) {
                viewModel.prepareQuickRouteFromGps(resolved.first, resolved.second)
                onOpenRouteResults()
            } else {
                viewModel.openQuickDestinationEditor(dest.slot)
            }
        }
    }

    val quickLocationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pending = pendingQuickDest
        pendingQuickDest = null
        if (pending != null) {
            if (!viewModel.isLocationEnabled()) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            launchQuickRouteWithGps(pending)
        }
    }

    val isSearchMode = state.activeField != null
    val activeQuery = when (state.activeField) {
        ActiveStopField.FROM -> state.fromQuery
        ActiveStopField.TO -> state.toQuery
        null -> ""
    }
    var showTimePickerDialog by remember { mutableStateOf(false) }
    var mapPickerField by remember { mutableStateOf<ActiveStopField?>(null) }
    var mapPickerStops by remember { mutableStateOf<List<StopGroup>>(emptyList()) }

    BackHandler(enabled = isSearchMode) {
        focusManager.clearFocus()
        viewModel.dismissSuggestions()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Верхний единый блок поиска в стиле Jakdojade (без лишнего TopAppBar)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .animateContentSize(animationSpec = tween(220, easing = FastOutSlowInEasing))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedVisibility(
                    visible = isSearchMode,
                    enter = expandVertically(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(140))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.dismissSuggestions()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
                        }
                        Text(
                            text = if (state.activeField == ActiveStopField.FROM) s.pointALabel else s.pointBLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Поле 1: Откуда (зелёная точка)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(DeparturePillGreen)
                        )
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (state.fromQuery.isEmpty()) {
                                Text(
                                    text = s.myLocationGps,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = state.fromQuery,
                                onValueChange = viewModel::onFromQueryChange,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
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
                                modifier = Modifier.size(30.dp)
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
                }

                // Поле 2: Куда (синяя точка) + кнопка Swap
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(ArrivalPillBlue)
                        )
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (state.toQuery.isEmpty()) {
                                Text(
                                    text = s.chooseStopPlaceholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = state.toQuery,
                                onValueChange = viewModel::onToQueryChange,
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
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
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = s.clear,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = viewModel::swapFromAndTo,
                            enabled = state.fromEndpoint != null || state.toEndpoint != null,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapVert,
                                contentDescription = s.swapTooltip,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Нижняя строка капсул: Время отправления/прибытия (18:10 ▾) + Опции ⚙
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val timeChipText = if (state.plannedTime.isNow) {
                        s.nowLabel
                    } else {
                        val today = LocalDate.now(ZoneId.of("Europe/Warsaw"))
                        val selectedDate = state.plannedTime.resolveDate(today)
                        val dateLabel = when (selectedDate) {
                            today -> ""
                            today.plusDays(1) -> "${s.tomorrowLabel}, "
                            today.plusDays(2) -> "${s.dayAfterTomorrowLabel}, "
                            else -> "${selectedDate.format(DateTimeFormatter.ofPattern("dd.MM"))}, "
                        }
                        val clock = TimeFormatUtils.formatClock(
                            state.plannedTime.timeSec ?: LocalTime.now(ZoneId.of("Europe/Warsaw")).toSecondOfDay()
                        )
                        val modePrefix = if (state.plannedTime.mode == TimeQueryMode.ARRIVE_BY) {
                            "${s.arriveByLabel} "
                        } else {
                            ""
                        }
                        "$modePrefix$dateLabel$clock"
                    }

                    Surface(
                        modifier = Modifier.clickable { showTimePickerDialog = true },
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = timeChipText,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.clickable(onClick = onOpenSettings),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = s.optionsLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Box {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = s.tripFiltersTooltip,
                                    modifier = Modifier.size(18.dp)
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
                }

                AnimatedVisibility(
                    visible = state.toEndpoint != null && !isSearchMode,
                    enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(140))
                ) {
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
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Основное содержимое ниже поиска
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isSearchMode) {
                item(key = "pick_on_map_action") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val field = state.activeField ?: return@clickable
                                focusManager.clearFocus()
                                mapPickerField = field
                                scope.launch {
                                    mapPickerStops = viewModel.getAllStopGroupsForMap()
                                }
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = s.pickOnMapLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Если база пуста в режиме поиска
            if (isSearchMode && !state.graphReady) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
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

            // История остановок в поиске (до ввода 2 букв)
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
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
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
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Подсказки остановок и адресов (если введён номер дома — адреса поднимаем наверх)
            val looksLikeAddress = activeQuery.any { it.isDigit() }
            val visibleStops = if (state.addressResults.isNotEmpty() || looksLikeAddress) {
                state.searchResults.take(6)
            } else {
                state.searchResults
            }
            val showAddressCard = isSearchMode && (state.addressResults.isNotEmpty() || state.isSearchingAddresses)
            val showStopsCard = isSearchMode && visibleStops.isNotEmpty()

            if (showAddressCard && (looksLikeAddress || !showStopsCard)) {
                item(key = "address_results_top") {
                    AddressResultsCard(
                        addresses = state.addressResults,
                        isLoading = state.isSearchingAddresses,
                        onSelect = { address ->
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
                }
            }

            if (showStopsCard) {
                item(key = "stop_results_card") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column {
                            visibleStops.forEachIndexed { idx, group ->
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
                                if (idx < visibleStops.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (showAddressCard && !looksLikeAddress && showStopsCard) {
                item(key = "address_results_bottom") {
                    AddressResultsCard(
                        addresses = state.addressResults,
                        isLoading = state.isSearchingAddresses,
                        onSelect = { address ->
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
                }
            }

            // Основное меню (скрывается при фокусе на поиске)
            if (!isSearchMode) {
                // 1. Быстрые маршруты (в едином AMOLED-стиле)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = s.quickRoutesTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
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
                                        if (!viewModel.hasLocationPermission()) {
                                            pendingQuickDest = dest
                                            quickLocationPermissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                                )
                                            )
                                        } else {
                                            if (!viewModel.isLocationEnabled()) {
                                                runCatching {
                                                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                                }
                                            }
                                            launchQuickRouteWithGps(dest)
                                        }
                                    },
                                    onLongClick = { viewModel.openQuickDestinationEditor(dest.slot) }
                                )
                            }
                        }
                    }
                }

                // 2. Ваши маршруты (История поездок в стиле Jakdojade с зелёной/синей точкой)
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
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
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
                                                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(9.dp)
                                                        .clip(CircleShape)
                                                        .background(DeparturePillGreen)
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .width(2.dp)
                                                        .height(14.dp)
                                                        .background(MaterialTheme.colorScheme.outline)
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .size(9.dp)
                                                        .clip(CircleShape)
                                                        .background(ArrivalPillBlue)
                                                )
                                            }
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = item.fromName ?: s.myLocation,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = item.toName,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
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
                                            HorizontalDivider(
                                                modifier = Modifier.padding(horizontal = 14.dp),
                                                color = MaterialTheme.colorScheme.outlineVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Если база расписаний ещё не загружена — показываем подсказку синхронизации
                if (!state.graphReady) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
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
                }
            }
        }
    }

    // Диалог выбора остановки или точки на карте
    val pickerField = mapPickerField
    if (pickerField != null) {
        val initLat = state.userLocation?.latitude ?: AppConfig.POZNAN_CENTER_LAT
        val initLon = state.userLocation?.longitude ?: AppConfig.POZNAN_CENTER_LON
        MapLocationPickerDialog(
            title = if (pickerField == ActiveStopField.FROM) s.pointALabel else s.pointBLabel,
            localMapFile = remember { viewModel.getLocalMapFile() },
            initialLat = initLat,
            initialLon = initLon,
            stopGroups = mapPickerStops,
            onPickStop = { group ->
                mapPickerField = null
                when (pickerField) {
                    ActiveStopField.FROM -> viewModel.selectFromStop(group)
                    ActiveStopField.TO -> {
                        if (viewModel.selectToStop(group)) {
                            viewModel.prepareRouteSearch(RouteEndpoint.Stop(group))
                            onOpenRouteResults()
                        }
                    }
                }
            },
            onPickLocation = { addr ->
                mapPickerField = null
                when (pickerField) {
                    ActiveStopField.FROM -> viewModel.selectFromAddress(addr)
                    ActiveStopField.TO -> {
                        viewModel.selectToAddress(addr)
                        viewModel.prepareRouteSearch(RouteEndpoint.Address(addr))
                        onOpenRouteResults()
                    }
                }
            },
            onDismiss = { mapPickerField = null }
        )
    }

    // Диалог планирования времени отправления / прибытия
    if (showTimePickerDialog) {
        PlanTimeDialog(
            initial = state.plannedTime,
            onDismiss = { showTimePickerDialog = false },
            onResetNow = {
                viewModel.resetPlannedTimeToNow()
                showTimePickerDialog = false
            },
            onConfirm = { planned ->
                viewModel.setPlannedTime(planned)
                showTimePickerDialog = false
            }
        )
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlanTimeDialog(
    initial: PlannedTime,
    onDismiss: () -> Unit,
    onResetNow: () -> Unit,
    onConfirm: (PlannedTime) -> Unit
) {
    val s = LocalStrings.current
    val warsawZone = remember { ZoneId.of("Europe/Warsaw") }
    val today = remember { LocalDate.now(warsawZone) }
    val now = remember { LocalTime.now(warsawZone) }
    val initialMinutes = (initial.timeSec ?: now.toSecondOfDay()) / 60
    var mode by remember { mutableStateOf(initial.mode) }
    var selectedDate by remember { mutableStateOf(initial.resolveDate(today)) }
    var hour by remember { mutableIntStateOf((initialMinutes / 60) % 24) }
    var minute by remember { mutableIntStateOf(initialMinutes % 60) }
    var showCalendarPicker by remember { mutableStateOf(false) }

    val dateFormatter = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy (EEE)", Locale.getDefault()) }

    if (showCalendarPicker) {
        val initialMillis = selectedDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showCalendarPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            if (!picked.isBefore(today)) {
                                selectedDate = picked
                            }
                        }
                        showCalendarPicker = false
                    }
                ) {
                    Text(s.doneLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCalendarPicker = false }) {
                    Text(s.close)
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.planTimeTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Режим: Отправление / Прибытие
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = mode == TimeQueryMode.DEPART_AT,
                        onClick = { mode = TimeQueryMode.DEPART_AT },
                        label = { Text(s.departAtLabel) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = mode == TimeQueryMode.ARRIVE_BY,
                        onClick = { mode = TimeQueryMode.ARRIVE_BY },
                        label = { Text(s.arriveByLabel) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Выбор даты: Сегодня / Завтра / Послезавтра
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedDate == today,
                        onClick = { selectedDate = today },
                        label = { Text(s.todayLabel) }
                    )
                    FilterChip(
                        selected = selectedDate == today.plusDays(1),
                        onClick = { selectedDate = today.plusDays(1) },
                        label = { Text(s.tomorrowLabel) }
                    )
                    FilterChip(
                        selected = selectedDate == today.plusDays(2),
                        onClick = { selectedDate = today.plusDays(2) },
                        label = { Text(s.dayAfterTomorrowLabel) }
                    )
                }

                // Переключатель произвольной даты + кнопка календаря
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedDate.isAfter(today)) {
                                    selectedDate = selectedDate.minusDays(1)
                                }
                            },
                            enabled = selectedDate.isAfter(today),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = null)
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showCalendarPicker = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = selectedDate.format(dateFormatter),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(
                            onClick = { selectedDate = selectedDate.plusDays(1) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    }
                }

                // Быстрые пресеты времени
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = false,
                        onClick = onResetNow,
                        label = { Text(s.nowLabel) }
                    )
                    listOf(15, 30, 60).forEach { addMin ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                val targetDateTime = LocalTime.now(warsawZone).plusMinutes(addMin.toLong())
                                if (targetDateTime.isBefore(LocalTime.now(warsawZone))) {
                                    selectedDate = today.plusDays(1)
                                } else {
                                    selectedDate = today
                                }
                                hour = targetDateTime.hour
                                minute = targetDateTime.minute
                            },
                            label = { Text("+$addMin ${s.minShort}") }
                        )
                    }
                }

                // Выбор часа и минут
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimeStepper(
                        value = hour,
                        onDecrement = { hour = (hour + 23) % 24 },
                        onIncrement = { hour = (hour + 1) % 24 }
                    )
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                    TimeStepper(
                        value = minute,
                        onDecrement = { minute = (minute + 55) % 60 },
                        onIncrement = { minute = (minute + 5) % 60 }
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.close)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val epochDay = if (selectedDate == today) null else selectedDate.toEpochDay()
                    onConfirm(
                        PlannedTime(
                            mode = mode,
                            timeSec = hour * 3600 + minute * 60,
                            dateEpochDay = epochDay
                        )
                    )
                }
            ) {
                Text(s.doneLabel)
            }
        }
    )
}

@Composable
private fun TimeStepper(
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onIncrement) {
            Icon(Icons.Default.Add, contentDescription = null)
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = String.format(Locale.US, "%02d", value),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
            )
        }
        IconButton(onClick = onDecrement) {
            Icon(Icons.Default.Remove, contentDescription = null)
        }
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
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Place,
            contentDescription = null,
            tint = ArrivalPillBlue,
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

    Surface(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
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
private fun AddressResultsCard(
    addresses: List<AddressResult>,
    isLoading: Boolean,
    onSelect: (AddressResult) -> Unit
) {
    val s = LocalStrings.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        tint = ArrivalPillBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = s.addressesSectionTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = ArrivalPillBlue
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = ArrivalPillBlue
                    )
                }
            }
            if (addresses.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                addresses.forEachIndexed { idx, address ->
                    AddressSearchRow(
                        address = address,
                        onClick = { onSelect(address) }
                    )
                    if (idx < addresses.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
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
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
    val nowSec = LocalTime.now(ZoneId.of("Europe/Warsaw")).toSecondOfDay()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
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
