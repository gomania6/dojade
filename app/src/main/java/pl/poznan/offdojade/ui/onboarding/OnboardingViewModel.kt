package pl.poznan.offdojade.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.gtfs.GtfsImporter
import pl.poznan.offdojade.data.gtfs.PrebuiltDbDownloader
import pl.poznan.offdojade.data.local.PreferencesRepository
import pl.poznan.offdojade.data.map.MapDownloader
import pl.poznan.offdojade.domain.routing.TransitGraphRepository

data class OnboardingUiState(
    val mapDownloaded: Boolean = false,
    val mapDownloading: Boolean = false,
    val mapSkipped: Boolean = false,
    val mapPercent: Int = 0,
    val mapDownloadedMb: Double = 0.0,
    val mapTotalMb: Double = 0.0,
    val mapError: String? = null,

    val gtfsReady: Boolean = false,
    val gtfsSyncing: Boolean = false,
    val gtfsProgress: Float = 0f,
    val gtfsStage: String = "Ожидание загрузки расписания ZTM Poznań",
    val gtfsRowsImported: Int = 0,
    val gtfsError: String? = null
) {
    val canContinue: Boolean
        get() = gtfsReady && (mapDownloaded || mapSkipped || !mapDownloading)
}

class OnboardingViewModel(
    private val mapDownloader: MapDownloader,
    private val prebuiltDbDownloader: PrebuiltDbDownloader,
    private val gtfsImporter: GtfsImporter,
    private val transitGraphRepository: TransitGraphRepository,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private var mapJob: Job? = null
    private var gtfsJob: Job? = null

    init {
        checkInitialStatus()
    }

    private fun checkInitialStatus() {
        viewModelScope.launch {
            val mapExists = mapDownloader.isMapDownloaded()
            val dbHasData = transitGraphRepository.hasDatabaseData()
            _uiState.update {
                it.copy(
                    mapDownloaded = mapExists,
                    mapPercent = if (mapExists) 100 else 0,
                    gtfsReady = dbHasData,
                    gtfsProgress = if (dbHasData) 1f else 0f,
                    gtfsStage = if (dbHasData) "Расписание ZTM уже загружено" else it.gtfsStage
                )
            }
            if (!dbHasData) {
                startGtfsSync()
            }
            if (!mapExists) {
                startMapDownload()
            }
        }
    }

    fun startMapDownload() {
        if (mapJob?.isActive == true) return
        mapJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    mapDownloading = true,
                    mapSkipped = false,
                    mapError = null
                )
            }
            mapDownloader.downloadMap()
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            mapDownloading = false,
                            mapError = e.localizedMessage ?: "Не удалось скачать poznan.pmtiles"
                        )
                    }
                }
                .collect { progress ->
                    _uiState.update {
                        it.copy(
                            mapDownloading = !progress.isCompleted,
                            mapDownloaded = progress.isCompleted,
                            mapPercent = progress.percent,
                            mapDownloadedMb = progress.downloadedMb,
                            mapTotalMb = progress.totalMb,
                            mapError = progress.errorMessage
                        )
                    }
                }
        }
    }

    fun skipMapForNow() {
        mapJob?.cancel()
        _uiState.update {
            it.copy(
                mapDownloading = false,
                mapSkipped = true,
                mapError = null
            )
        }
    }

    fun startGtfsSync() {
        if (gtfsJob?.isActive == true) return
        gtfsJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    gtfsSyncing = true,
                    gtfsError = null,
                    gtfsProgress = 0.02f,
                    gtfsStage = if (AppConfig.isPrebuiltScheduleConfigured()) {
                        "Проверка готовой базы на GitHub Releases…"
                    } else {
                        "Загрузка GTFS ZTM Poznań…"
                    }
                )
            }

            var prebuiltSucceeded = false
            if (AppConfig.isPrebuiltScheduleConfigured()) {
                try {
                    prebuiltDbDownloader.syncFromGitHubReleases(forceDownload = false).collect { progress ->
                        _uiState.update {
                            it.copy(
                                gtfsProgress = progress.progress,
                                gtfsStage = progress.stage,
                                gtfsRowsImported = progress.rowsImported
                            )
                        }
                        if (progress.isFinished) {
                            prebuiltSucceeded = true
                        }
                    }
                } catch (_: Exception) {
                    _uiState.update {
                        it.copy(gtfsStage = "GitHub недоступен — загрузка напрямую с ZTM Poznań…")
                    }
                }
            }

            if (prebuiltSucceeded) {
                finalizeGraphReload()
                return@launch
            }

            gtfsImporter.downloadAndImport()
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            gtfsSyncing = false,
                            gtfsError = e.localizedMessage ?: "Ошибка загрузки расписания ZTM"
                        )
                    }
                }
                .collect { progress ->
                    _uiState.update {
                        it.copy(
                            gtfsProgress = progress.progress,
                            gtfsStage = progress.stage,
                            gtfsRowsImported = progress.rowsImported
                        )
                    }
                    if (progress.isFinished) {
                        finalizeGraphReload()
                    }
                }
        }
    }

    private suspend fun finalizeGraphReload() {
        _uiState.update { it.copy(gtfsStage = "Построение быстрого графа RAPTOR…") }
        transitGraphRepository.reloadForToday()
        preferencesRepository.setOnboardingCompleted(true)
        _uiState.update {
            it.copy(
                gtfsSyncing = false,
                gtfsReady = true,
                gtfsProgress = 1f,
                gtfsStage = "Готово к работе офлайн!"
            )
        }
    }

    fun markOnboardingDone(onDone: () -> Unit) {
        viewModelScope.launch {
            preferencesRepository.setOnboardingCompleted(true)
            onDone()
        }
    }
}
