package pl.poznan.offdojade.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessible
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import pl.poznan.offdojade.domain.model.AppLanguage
import pl.poznan.offdojade.domain.model.RoutePreference
import pl.poznan.offdojade.domain.model.ThemeMode
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.ui.localization.LocalStrings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSyncAndMap: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val trip = settings.tripSettings
    val s = LocalStrings.current

    var excludeLineInput by remember { mutableStateOf("") }
    var preferredLineInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
                    }
                },
                title = {
                    Text(
                        text = s.settingsTitle,
                        fontWeight = FontWeight.Bold
                    )
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
            // === 1. Язык и тема ===
            item {
                SectionHeader(s.appearanceSection)
                Spacer(Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Язык
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = s.languageLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AppLanguage.entries.forEach { lang ->
                                FilterChip(
                                    selected = settings.language == lang,
                                    onClick = { viewModel.setLanguage(lang) },
                                    label = { Text(lang.nativeName) }
                                )
                            }
                        }

                        HorizontalDivider()

                        // Тема оформления
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = s.themeLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    label = { Text(s.themeName(mode)) }
                                )
                            }
                        }
                    }
                }
            }

            // === 2. Настройки поездок (как в Jakdojade) ===
            item {
                SectionHeader(s.tripSettingsSection)
                Spacer(Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Профиль маршрута: Удобно / Лучшее (по умолчанию) / Быстрое
                        Text(
                            text = s.routeTypeLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            RoutePreference.entries.forEach { pref ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.setRoutePreference(pref) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = trip.routePreference == pref,
                                        onClick = { viewModel.setRoutePreference(pref) }
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = s.routePreferenceName(pref),
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = s.routePreferenceDesc(pref),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        HorizontalDivider()

                        // Избегать пересадок
                        SettingsSwitchRow(
                            title = s.avoidTransfers,
                            subtitle = s.avoidTransfersDesc,
                            checked = trip.avoidTransfers,
                            onCheckedChange = viewModel::setAvoidTransfers
                        )

                        HorizontalDivider()

                        // Все виды транспорта
                        Text(
                            text = s.transportModesLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TransportMode.entries.forEach { mode ->
                                val isSelected = mode in trip.enabledTransportModes &&
                                    !(mode == TransportMode.BUS && trip.excludeBuses)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.toggleTransportMode(mode) },
                                    label = { Text(s.transportModeName(mode)) }
                                )
                            }
                        }

                        HorizontalDivider()

                        // Исключить линии
                        LineChipEditor(
                            title = s.excludeLinesLabel,
                            placeholder = s.linesInputHint,
                            addLabel = s.addAction,
                            value = excludeLineInput,
                            onValueChange = { excludeLineInput = it },
                            lines = trip.excludedLines.sorted(),
                            onAdd = {
                                if (excludeLineInput.isNotBlank()) {
                                    excludeLineInput.split(",", " ", ";")
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .forEach { viewModel.addExcludedLine(it) }
                                    excludeLineInput = ""
                                }
                            },
                            onRemove = viewModel::removeExcludedLine
                        )

                        HorizontalDivider()

                        // Предпочитаемые линии
                        LineChipEditor(
                            title = s.preferredLinesLabel,
                            placeholder = s.linesInputHint,
                            addLabel = s.addAction,
                            value = preferredLineInput,
                            onValueChange = { preferredLineInput = it },
                            lines = trip.preferredLines.sorted(),
                            onAdd = {
                                if (preferredLineInput.isNotBlank()) {
                                    preferredLineInput.split(",", " ", ";")
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .forEach { viewModel.addPreferredLine(it) }
                                    preferredLineInput = ""
                                }
                            },
                            onRemove = viewModel::removePreferredLine
                        )

                        HorizontalDivider()

                        // Исключить автобусы
                        SettingsSwitchRow(
                            title = s.excludeBuses,
                            subtitle = s.excludeBusesDesc,
                            checked = trip.excludeBuses,
                            onCheckedChange = viewModel::setExcludeBuses,
                            leadingIcon = {
                                Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        )

                        HorizontalDivider()

                        // Исключить зоны (A, B, C, D)
                        Text(
                            text = s.excludeZonesLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("A", "B", "C", "D").forEach { zone ->
                                FilterChip(
                                    selected = zone in trip.excludedZones,
                                    onClick = { viewModel.toggleExcludedZone(zone) },
                                    label = { Text("${s.zonePrefix} $zone") }
                                )
                            }
                        }

                        HorizontalDivider()

                        // Только с низким полом
                        SettingsSwitchRow(
                            title = s.lowFloorOnly,
                            subtitle = s.lowFloorOnlyDesc,
                            checked = trip.lowFloorOnly,
                            onCheckedChange = viewModel::setLowFloorOnly,
                            leadingIcon = {
                                Icon(Icons.Default.Accessible, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        )

                        HorizontalDivider()

                        // Льготный билет (Ulgowy 50%)
                        SettingsSwitchRow(
                            title = s.ulgowyTicketTitle,
                            subtitle = s.ulgowyTicketDesc,
                            checked = trip.isUlgowyTicket,
                            onCheckedChange = viewModel::setUlgowyTicket
                        )

                        HorizontalDivider()

                        // Минимальное время пересадки
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = s.minTransferTimeLabel,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = s.minTransferValue(trip.minTransferMinutes),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = trip.minTransferMinutes.toFloat(),
                            onValueChange = { viewModel.setMinTransferMinutes(it.roundToInt()) },
                            valueRange = 0f..15f,
                            steps = 14
                        )

                        OutlinedButton(
                            onClick = viewModel::resetTripSettings,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(s.resetTripSettings)
                        }
                    }
                }
            }

            // === 3. Данные и карта ===
            item {
                SectionHeader(s.dataAndMapSection)
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenSyncAndMap),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = s.openSyncAndMapScreen,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (leadingIcon != null) {
            leadingIcon()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineChipEditor(
    title: String,
    placeholder: String,
    addLabel: String,
    value: String,
    onValueChange: (String) -> Unit,
    lines: List<String>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(placeholder) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            )
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = addLabel)
            }
        }
        if (lines.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                lines.forEach { line ->
                    InputChip(
                        selected = true,
                        onClick = { onRemove(line) },
                        label = { Text(line, fontWeight = FontWeight.Bold) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}
