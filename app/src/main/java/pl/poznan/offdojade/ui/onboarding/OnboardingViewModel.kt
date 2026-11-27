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

enum class OnboardingGtfsStage {
    WAITING,
    ALREADY_LOADED,
    CHECKING_GITHUB,
    DOWNLOADING_DIRECT,
    FALLBACK_DIRECT,
    BUILDING_GRAPH,
    READY_OFFLINE,
    CUSTOM
}

data class OnboardingUiState(
    val mapDownloaded: Boolean = false,
    val mapDownloading: Boolean = false,
    val mapSkipped: Boolean = false,
    val mapPercent: Int = 0,
    val mapDownloadedMb: Double = 0.0,
    val mapTotalMb: Double = 0.0,
    val mapError: String? = null,
    val mapErrorIsDefault: Boolean = false,

    val gtfsReady: Boolean = false,
    val gtfsSyncing: Boolean = false,
    val gtfsProgress: Float = 0f,
    val gtfsStageType: OnboardingGtfsStage = OnboardingGtfsStage.WAITING,
    val gtfsStageCustom: String = "",
    val gtfsRowsImported: Int = 0,
    val gtfsError: String? = null,
    val gtfsErrorIsDefault: Boolean = false
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
                    gtfsStageType = if (dbHasData) OnboardingGtfsStage.ALREADY_LOADED else it.gtfsStageType
                )
            }
            if (!dbHasData) {
                // Сначала скачиваем расписание на полной скорости канала (не деля трафик с 90 МБ картой),
                // а оффлайн-карту автоматически подхватываем сразу после готовности расписания
                startGtfsSync(autoStartMapAfter = !mapExists)
            } else {
                // Прогреваем граф и индекс остановок пока пользователь на экране ознакомления
                viewModelScope.launch {
                    transitGraphRepository.ensureLoadedForToday()
                    transitGraphRepository.searchStopGroups("", limit = 1)
                }
                if (!mapExists) {
                    startMapDownload()
                }
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
                    mapError = null,
                    mapErrorIsDefault = false
                )
            }
            mapDownloader.downloadMap()
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            mapDownloading = false,
                            mapError = e.localizedMessage,
                            mapErrorIsDefault = e.localizedMessage.isNullOrBlank()
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
                            mapError = progress.errorMessage,
                            mapErrorIsDefault = false
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

    fun startGtfsSync(autoStartMapAfter: Boolean = false) {
        if (gtfsJob?.isActive == true) return
        gtfsJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    gtfsSyncing = true,
                    gtfsError = null,
                    gtfsErrorIsDefault = false,
                    gtfsProgress = 0.02f,
                    gtfsStageType = if (AppConfig.isPrebuiltScheduleConfigured()) {
                        OnboardingGtfsStage.CHECKING_GITHUB
                    } else {
                        OnboardingGtfsStage.DOWNLOADING_DIRECT
                    },
                    gtfsStageCustom = ""
                )
            }

            var prebuiltSucceeded = false
            if (AppConfig.isPrebuiltScheduleConfigured()) {
                try {
                    prebuiltDbDownloader.syncFromGitHubReleases(forceDownload = false).collect { progress ->
                        _uiState.update {
                            it.copy(
                                gtfsProgress = progress.progress,
                                gtfsStageType = OnboardingGtfsStage.CUSTOM,
                                gtfsStageCustom = progress.stage,
                                gtfsRowsImported = progress.rowsImported
                            )
                        }
                        if (progress.isFinished) {
                            prebuiltSucceeded = true
                        }
                    }
                } catch (_: Exception) {
                    _uiState.update {
                        it.copy(
                            gtfsStageType = OnboardingGtfsStage.FALLBACK_DIRECT,
                            gtfsStageCustom = ""
                        )
                    }
                }
            }

            if (prebuiltSucceeded) {
                finalizeGraphReload(autoStartMapAfter)
                return@launch
            }

            gtfsImporter.downloadAndImport()
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            gtfsSyncing = false,
                            gtfsError = e.localizedMessage,
                            gtfsErrorIsDefault = e.localizedMessage.isNullOrBlank()
                        )
                    }
                }
                .collect { progress ->
                    _uiState.update {
                        it.copy(
                            gtfsProgress = progress.progress,
                            gtfsStageType = OnboardingGtfsStage.CUSTOM,
                            gtfsStageCustom = progress.stage,
                            gtfsRowsImported = progress.rowsImported
                        )
                    }
                    if (progress.isFinished) {
                        finalizeGraphReload(autoStartMapAfter)
                    }
                }
        }
    }

    private suspend fun finalizeGraphReload(autoStartMapAfter: Boolean = false) {
        _uiState.update {
            it.copy(
                gtfsStageType = OnboardingGtfsStage.BUILDING_GRAPH,
                gtfsStageCustom = ""
            )
        }
        transitGraphRepository.reloadForToday()
        preferencesRepository.setOnboardingCompleted(true)
        _uiState.update {
            it.copy(
                gtfsSyncing = false,
                gtfsReady = true,
                gtfsProgress = 1f,
                gtfsStageType = OnboardingGtfsStage.READY_OFFLINE,
                gtfsStageCustom = ""
            )
        }
        if (autoStartMapAfter && !_uiState.value.mapDownloaded && !_uiState.value.mapSkipped) {
            startMapDownload()
        }
    }

    fun markOnboardingDone(onDone: () -> Unit) {
        viewModelScope.launch {
            preferencesRepository.setOnboardingCompleted(true)
            onDone()
        }
    }
}
