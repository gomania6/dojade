package pl.poznan.offdojade.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import pl.poznan.offdojade.ui.theme.LiveGreen
import java.util.Locale

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "OffDojade • ZTM Poznań",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Офлайн-навигатор общественного транспорта Познани без рекламы и серверов. " +
                    "Подготовим офлайн-карту и актуальное расписание ZTM.",
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
                                text = "Офлайн-карта Познани (.pmtiles)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            val statusText = when {
                                state.mapDownloaded -> "Карта сохранена на устройстве"
                                state.mapSkipped -> "Пропущено — карта будет работать в базовом режиме"
                                state.mapDownloading && state.mapTotalMb > 0 -> String.format(
                                    Locale.US,
                                    "Загрузка: %d%% (%.1f / %.1f МБ)",
                                    state.mapPercent,
                                    state.mapDownloadedMb,
                                    state.mapTotalMb
                                )
                                state.mapDownloading -> String.format(
                                    Locale.US,
                                    "Загрузка: %.1f МБ…",
                                    state.mapDownloadedMb
                                )
                                else -> "Источник: GitHub Releases (AppConfig.MAP_DOWNLOAD_URL)"
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

                    if (state.mapError != null) {
                        Text(
                            text = state.mapError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (!state.mapDownloaded) {
                            TextButton(onClick = viewModel::skipMapForNow) {
                                Text("Пропустить пока")
                            }
                            OutlinedButton(
                                onClick = viewModel::startMapDownload,
                                enabled = !state.mapDownloading
                            ) {
                                Text(if (state.mapError != null) "Повторить" else "Скачать карту")
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
                                text = "Расписание ZTM Poznań (GTFS)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = state.gtfsStage + if (state.gtfsRowsImported > 0) {
                                    " • ${state.gtfsRowsImported} записей"
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

                    if (state.gtfsError != null) {
                        Text(
                            text = state.gtfsError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = viewModel::startGtfsSync,
                            enabled = !state.gtfsSyncing
                        ) {
                            Text(if (state.gtfsReady) "Обновить расписание" else "Повторить загрузку")
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { viewModel.markOnboardingDone(onFinished) },
                enabled = state.canContinue || !state.gtfsSyncing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = if (state.gtfsReady) "Начать поездки" else "Перейти на главный экран",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
