package pl.poznan.offdojade.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import pl.poznan.offdojade.data.local.QuickDestination
import pl.poznan.offdojade.data.local.QuickDestinationSlot
import pl.poznan.offdojade.domain.model.NearbyStopBoard
import pl.poznan.offdojade.domain.model.StopGroup
import pl.poznan.offdojade.ui.components.LineBadge
import pl.poznan.offdojade.ui.components.RealtimeStatusBadge
import pl.poznan.offdojade.ui.components.TimeFormatUtils
import pl.poznan.offdojade.ui.theme.DelayRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenRouteResults: (fromStopId: String?, targetStopId: String) -> Unit,
    onOpenSyncSettings: () -> Unit,
    viewModel: HomeViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val realtimeStatus by viewModel.realtimeStatus.collectAsStateWithLifecycle()
    val quickDestinations by viewModel.quickDestinations.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteStopIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "OffDojade",
                            fontWeight = FontWeight.Bold
                        )
                        RealtimeStatusBadge(status = realtimeStatus)
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshDashboard) {
                        Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                    IconButton(onClick = onOpenSyncSettings) {
                        Icon(Icons.Default.CloudSync, contentDescription = "Синхронизация и карта")
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
            // 1. Блок выбора маршрута: Пункт А (Откуда) -> Пункт Б (Куда)
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Пункт А (Откуда)
                                OutlinedTextField(
                                    value = state.fromQuery,
                                    onValueChange = viewModel::onFromQueryChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    label = { Text("Пункт А (Откуда)") },
                                    placeholder = { Text("Моё местоположение (GPS)") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (state.fromStop == null) Icons.Default.MyLocation else Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    trailingIcon = {
                                        if (state.fromQuery.isNotEmpty() || state.fromStop != null) {
                                            IconButton(onClick = viewModel::resetFromToGps) {
                                                Icon(Icons.Default.Close, contentDescription = "Сбросить на GPS")
                                            }
                                        }
                                    },
                                    singleLine = true
                                )

                                // Пункт Б (Куда)
                                OutlinedTextField(
                                    value = state.toQuery,
                                    onValueChange = viewModel::onToQueryChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(14.dp),
                                    label = { Text("Пункт Б (Куда)") },
                                    placeholder = { Text("Выберите остановку…") },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    trailingIcon = {
                                        if (state.toQuery.isNotEmpty() || state.toStop != null) {
                                            IconButton(onClick = viewModel::clearToStop) {
                                                Icon(Icons.Default.Close, contentDescription = "Очистить")
                                            }
                                        }
                                    },
                                    singleLine = true
                                )
                            }

                            IconButton(
                                onClick = viewModel::swapFromAndTo,
                                enabled = state.fromStop != null || state.toStop != null
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapVert,
                                    contentDescription = "Поменять Пункт А и Б"
                                )
                            }
                        }

                        if (state.toStop != null) {
                            Button(
                                onClick = {
                                    val target = state.toStop ?: return@Button
                                    onOpenRouteResults(state.fromStop?.primaryStopId, target.primaryStopId)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Directions,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Построить маршрут",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Результаты автодополнения (объединённые по названию остановки)
            if (state.searchResults.isNotEmpty() && state.activeField != null) {
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
                                        when (state.activeField) {
                                            ActiveStopField.FROM -> viewModel.selectFromStop(group)
                                            ActiveStopField.TO -> {
                                                viewModel.selectToStop(group)
                                                onOpenRouteResults(state.fromStop?.primaryStopId, group.primaryStopId)
                                            }
                                            null -> Unit
                                        }
                                    },
                                    onToggleFavorite = { viewModel.toggleFavoriteStop(group.primaryStopId) }
                                )
                                if (idx < state.searchResults.lastIndex) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp))
                                }
                            }
                        }
                    }
                }
            }

            // 2. 3 быстрые карточки в один тап: «Домой», «В центр», «На работу»
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Быстрые маршруты",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Нажмите для мгновенного маршрута • Удерживайте для смены остановки",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        quickDestinations.forEach { dest ->
                            QuickDestinationCard(
                                destination = dest,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    scope.launch {
                                        val stopId = viewModel.resolveQuickDestinationStopId(dest)
                                        if (stopId != null) {
                                            onOpenRouteResults(state.fromStop?.primaryStopId, stopId)
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

            // 3. Ближайшие остановки рядом (объединенные по названию)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ближайшие остановки",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (state.userLocation?.isFallbackCenter == true) {
                        Text(
                            text = "Центр Познани",
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
                                text = "Расписание ZTM еще не загружено",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Нажмите на значок облака вверху, чтобы скачать расписание ZTM Poznań и офлайн-карту.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = onOpenSyncSettings) {
                                Text("Открыть синхронизацию")
                            }
                        }
                    }
                }
            } else if (state.nearbyBoards.isEmpty()) {
                item {
                    Text(
                        text = "Ищем ближайшие остановки и отправления…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(state.nearbyBoards, key = { it.stopId }) { board ->
                    NearbyStopCard(
                        board = board,
                        onClick = { onOpenRouteResults(state.fromStop?.primaryStopId, board.stopId) }
                    )
                }
            }
        }
    }

    // Диалог выбора остановки для быстрой карточки (объединённые остановки)
    val editingSlot = state.editingSlot
    if (editingSlot != null) {
        val slotTitle = when (editingSlot) {
            QuickDestinationSlot.HOME -> "Домой"
            QuickDestinationSlot.CENTER -> "В центр"
            QuickDestinationSlot.WORK -> "На работу"
        }
        AlertDialog(
            onDismissRequest = viewModel::closeQuickDestinationEditor,
            title = { Text("Остановка для «$slotTitle»") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.slotSearchQuery,
                        onValueChange = viewModel::onSlotSearchQueryChange,
                        placeholder = { Text("Поиск остановки…") },
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
                                    val platformsText = if (group.platformCount > 1) " • ${group.platformCount} платф." else ""
                                    Text(
                                        text = "${group.zonesLabel}$platformsText",
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
                    Text("Закрыть")
                }
            }
        )
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
                text = destination.stopName ?: "Задать…",
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
            val platformSuffix = if (group.platformCount > 1) {
                " • объединено ${group.platformCount} платф."
            } else {
                ""
            }
            Text(
                text = "${group.zonesLabel}$platformSuffix",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = "Избранное",
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
                    val platformsInfo = if (board.platformCount > 1) " • ${board.platformCount} платф." else ""
                    Text(
                        text = "${board.distanceMeters} м • пешком ~${(board.walkTimeSeconds + 30) / 60} мин • Зона ${board.zoneId}$platformsInfo",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (board.departures.isEmpty()) {
                Text(
                    text = "Нет ближайших отправлений в ближайшие 90 мин",
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
                            text = dep.headsign.ifBlank { "По расписанию" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "через ${dep.minutesUntilDeparture} мин",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            val delayText = TimeFormatUtils.formatDelayBadge(dep.delaySeconds)
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
