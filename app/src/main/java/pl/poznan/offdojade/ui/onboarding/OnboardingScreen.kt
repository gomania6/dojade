package pl.poznan.offdojade.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import pl.poznan.offdojade.R
import pl.poznan.offdojade.ui.localization.LocalStrings
import pl.poznan.offdojade.ui.theme.LiveGreen
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    showBackButton: Boolean = false,
    onBack: () -> Unit = {},
    viewModel: OnboardingViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val s = LocalStrings.current

    val resolvedGtfsStage = when (state.gtfsStageType) {
        OnboardingGtfsStage.WAITING -> s.gtfsStageWaiting
        OnboardingGtfsStage.ALREADY_LOADED -> s.gtfsStageAlreadyLoaded
        OnboardingGtfsStage.CHECKING_GITHUB -> s.gtfsStageCheckingGithub
        OnboardingGtfsStage.DOWNLOADING_DIRECT -> s.gtfsStageDownloadingDirect
        OnboardingGtfsStage.FALLBACK_DIRECT -> s.gtfsStageFallbackDirect
        OnboardingGtfsStage.BUILDING_GRAPH -> s.gtfsStageBuildingGraph
        OnboardingGtfsStage.READY_OFFLINE -> s.gtfsStageReadyOffline
        OnboardingGtfsStage.CUSTOM -> state.gtfsStageCustom.ifBlank { s.gtfsStageDownloadingDirect }
    }
    val resolvedMapError = when {
        !state.mapError.isNullOrBlank() -> state.mapError
        state.mapErrorIsDefault -> s.mapDownloadFailed
        else -> null
    }
    val resolvedGtfsError = when {
        !state.gtfsError.isNullOrBlank() -> state.gtfsError
        state.gtfsErrorIsDefault -> s.gtfsDownloadFailed
        else -> null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (showBackButton) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.back)
                        }
                    },
                    title = {
                        Text(
                            text = s.syncAndMap,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = { viewModel.markOnboardingDone(onFinished) },
                    enabled = state.canContinue || (!state.gtfsSyncing && !state.mapDownloading),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (showBackButton) {
                            s.doneLabel
                        } else if (state.gtfsReady) {
                            s.startTrips
                        } else {
                            s.goToHome
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!showBackButton) {
                Spacer(Modifier.height(4.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_pyrtransit_logo),
                    contentDescription = "PyrTransit",
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    text = "PyrTransit • ZTM Poznań",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = s.onboardingSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 1. Карточка скачивания офлайн-карты poznan.pmtiles
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (state.mapDownloaded) Icons.Default.CheckCircle else Icons.Default.Map,
                            contentDescription = null,
                            tint = if (state.mapDownloaded) LiveGreen else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.offlineMapTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            val statusText = when {
                                state.mapDownloaded -> s.mapSaved
                                state.mapSkipped -> s.mapSkipped
                                state.mapDownloading && state.mapTotalMb > 0 -> String.format(
                                    Locale.US,
                                    "%d%% (%.1f / %.1f MB)",
                                    state.mapPercent,
                                    state.mapDownloadedMb,
                                    state.mapTotalMb
                                )
                                state.mapDownloading -> String.format(
                                    Locale.US,
                                    "%.1f MB…",
                                    state.mapDownloadedMb
                                )
                                else -> s.mapSourceHint
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (state.mapDownloading) {
                        if (state.mapTotalMb > 0) {
                            LinearProgressIndicator(
                                progress = { state.mapPercent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    } else if (state.mapDownloaded) {
                        LinearProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.fillMaxWidth(),
                            color = LiveGreen
                        )
                    }

                    if (resolvedMapError != null) {
                        Text(
                            text = resolvedMapError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (!state.mapDownloaded) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TextButton(onClick = viewModel::skipMapForNow) {
                                Text(s.skipForNow, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = viewModel::startMapDownload,
                                enabled = !state.mapDownloading
                            ) {
                                Text(
                                    text = if (resolvedMapError != null) s.retry else s.downloadMap,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 2. Карточка загрузки и парсинга расписания ZTM Poznań
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = if (state.gtfsReady) Icons.Default.CheckCircle else Icons.Default.Schedule,
                            contentDescription = null,
                            tint = if (state.gtfsReady) LiveGreen else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = s.gtfsScheduleTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = resolvedGtfsStage + if (state.gtfsRowsImported > 0) {
                                    s.recordsSuffix(state.gtfsRowsImported)
                                } else {
                                    ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { state.gtfsProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (state.gtfsReady) LiveGreen else MaterialTheme.colorScheme.primary
                    )

                    if (resolvedGtfsError != null) {
                        Text(
                            text = resolvedGtfsError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.startGtfsSync() },
                            enabled = !state.gtfsSyncing
                        ) {
                            Text(
                                text = if (state.gtfsReady) s.updateSchedule else s.retryDownload,
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
