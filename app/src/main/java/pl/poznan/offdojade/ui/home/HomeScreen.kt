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
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
    val fromFocusRequester = remember { FocusRequester() }
    val toFocusRequester = remember { FocusRequester() }

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
                    onClick = { fromFocusRequester.requestFocus() },
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
                                    .focusRequester(fromFocusRequester)
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
                    onClick = { toFocusRequester.requestFocus() },
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
                                    .focusRequester(toFocusRequester)
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
                        onClick = { showTimePickerDialog = true },
                        modifier = Modifier.weight(1f, fill = false),
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
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Surface(
                        onClick = onOpenSettings,
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
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
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
                        onClick = {
                            val field = state.activeField ?: return@Surface
                            focusManager.clearFocus()
                            mapPickerField = field
                            scope.launch {
                                mapPickerStops = viewModel.getAllStopGroupsForMap()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
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
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                        }
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
                        placeholder = {
                            Text(
                                text = s.searchStopPlaceholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
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

@OptIn(ExperimentalMaterial3Api::class)
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
    val initialDate = initial.resolveDate(today)

    val dateOptions = remember(today) {
        (0..14).map { today.plusDays(it.toLong()) }
    }
    val locale = remember(s.localeTag) { Locale.forLanguageTag(s.localeTag) }
    val wheelDateFmt = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }
    val dateLabels = remember(dateOptions, s.todayLabel, wheelDateFmt) {
        dateOptions.mapIndexed { idx, date ->
            if (idx == 0) {
                s.todayLabel
            } else {
                date.format(wheelDateFmt).replaceFirstChar { it.titlecase(locale) }
            }
        }
    }
    val hourLabels = remember { (0..23).map { String.format(Locale.US, "%02d", it) } }
    val minuteLabels = remember { (0..59).map { String.format(Locale.US, "%02d", it) } }

    var mode by remember { mutableStateOf(initial.mode) }
    var selectedDateIdx by remember {
        mutableIntStateOf(dateOptions.indexOf(initialDate).coerceIn(0, dateOptions.lastIndex))
    }
    var selectedHour by remember { mutableIntStateOf((initialMinutes / 60) % 24) }
    var selectedMinute by remember { mutableIntStateOf(initialMinutes % 60) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Верхняя строка: [ Отправление | Прибытие ] + Сбросить (адаптивно под узкие экраны и RU/UK/DE)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    modifier = Modifier.weight(1f, fill = false),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(modifier = Modifier.padding(3.dp)) {
                        listOf(
                            TimeQueryMode.DEPART_AT to s.departAtLabel,
                            TimeQueryMode.ARRIVE_BY to s.arriveByLabel
                        ).forEach { (tabMode, tabLabel) ->
                            val selected = mode == tabMode
                            Surface(
                                onClick = { mode = tabMode },
                                shape = RoundedCornerShape(50),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.background
                                } else {
                                    androidx.compose.ui.graphics.Color.Transparent
                                }
                            ) {
                                Text(
                                    text = tabLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onBackground
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = s.resetLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onResetNow)
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }

            // 3 барабана (Дата | Часы | Минуты) с общей центральной подсветкой как в Jakdojade
            val itemHeight = 44.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight * 5),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
                ) {}

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WheelPickerColumn(
                        items = dateLabels,
                        initialIndex = selectedDateIdx,
                        onSelectedIndexChange = { selectedDateIdx = it },
                        itemHeight = itemHeight,
                        modifier = Modifier.weight(1.75f)
                    )
                    WheelPickerColumn(
                        items = hourLabels,
                        initialIndex = selectedHour,
                        onSelectedIndexChange = { selectedHour = it },
                        itemHeight = itemHeight,
                        modifier = Modifier.weight(0.75f)
                    )
                    WheelPickerColumn(
                        items = minuteLabels,
                        initialIndex = selectedMinute,
                        onSelectedIndexChange = { selectedMinute = it },
                        itemHeight = itemHeight,
                        modifier = Modifier.weight(0.75f)
                    )
                }
            }

            // Кнопки Сохранить + Отменить
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val pickedDate = dateOptions.getOrElse(selectedDateIdx) { today }
                        val epochDay = if (pickedDate == today) null else pickedDate.toEpochDay()
                        onConfirm(
                            PlannedTime(
                                mode = mode,
                                timeSec = selectedHour * 3600 + selectedMinute * 60,
                                dateEpochDay = epochDay
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = s.saveLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = s.cancelLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun WheelPickerColumn(
    items: List<String>,
    initialIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    itemHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val safeInitial = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeInitial)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()

    val centerIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo
                .minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - viewportCenter) }
                ?.index
                ?.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                ?: safeInitial
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { centerIndex }.collect { idx ->
            onSelectedIndexChange(idx)
        }
    }

    LazyColumn(
        state = listState,
        flingBehavior = flingBehavior,
        contentPadding = PaddingValues(vertical = itemHeight * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.height(itemHeight * 5)
    ) {
        itemsIndexed(items) { index, label ->
            val distance = kotlin.math.abs(index - centerIndex)
            val alpha = when (distance) {
                0 -> 1f
                1 -> 0.52f
                else -> 0.24f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clickable {
                        scope.launch { listState.animateScrollToItem(index) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (distance == 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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

    val cardShape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier
            .clip(cardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = cardShape,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
