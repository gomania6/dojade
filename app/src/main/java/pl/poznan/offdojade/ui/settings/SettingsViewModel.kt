package pl.poznan.offdojade.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.poznan.offdojade.data.local.PreferencesRepository
import pl.poznan.offdojade.data.map.MapDownloader
import pl.poznan.offdojade.domain.model.AppLanguage
import pl.poznan.offdojade.domain.model.AppSettings
import pl.poznan.offdojade.domain.model.RoutePreference
import pl.poznan.offdojade.domain.model.ThemeMode
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.domain.model.WalkingPace
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import java.util.Locale

data class StorageStatusInfo(
    val stopsCount: Int = 0,
    val tripsCount: Int = 0,
    val buildDate: String = "",
    val mapSizeMb: String? = null,
    val isRebuildingCache: Boolean = false,
    val cacheRebuiltNotice: Boolean = false
)

class SettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val transitGraphRepository: TransitGraphRepository,
    private val mapDownloader: MapDownloader
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = preferencesRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _storageStatus = MutableStateFlow(StorageStatusInfo())
    val storageStatus: StateFlow<StorageStatusInfo> = _storageStatus.asStateFlow()

    init {
        refreshStorageInfo()
    }

    fun refreshStorageInfo() {
        viewModelScope.launch {
            val (stops, trips) = runCatching { transitGraphRepository.getDatabaseCounts() }
                .getOrDefault(0 to 0)
            val meta = runCatching { preferencesRepository.getSyncMetadata() }.getOrNull()
            val buildDate = meta?.prebuiltBuildDate?.take(10)
                ?: meta?.lastModified?.take(16)
                ?: "—"
            val mapFile = mapDownloader.getLocalMapFile()
            val mapMb = if (mapFile.exists() && mapFile.length() > 0L) {
                String.format(Locale.US, "%.1f", mapFile.length() / (1024.0 * 1024.0))
            } else {
                null
            }
            _storageStatus.update {
                it.copy(
                    stopsCount = stops,
                    tripsCount = trips,
                    buildDate = buildDate,
                    mapSizeMb = mapMb
                )
            }
        }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { preferencesRepository.setLanguage(language) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferencesRepository.setThemeMode(mode) }
    }

    fun setRoutePreference(pref: RoutePreference) {
        viewModelScope.launch { preferencesRepository.setRoutePreference(pref) }
    }

    fun setAvoidTransfers(avoid: Boolean) {
        viewModelScope.launch { preferencesRepository.setAvoidTransfers(avoid) }
    }

    fun toggleTransportMode(mode: TransportMode) {
        viewModelScope.launch { preferencesRepository.toggleTransportMode(mode) }
    }

    fun setExcludeBuses(exclude: Boolean) {
        viewModelScope.launch { preferencesRepository.setExcludeBuses(exclude) }
    }

    fun addExcludedLine(line: String) {
        viewModelScope.launch { preferencesRepository.addExcludedLine(line) }
    }

    fun removeExcludedLine(line: String) {
        viewModelScope.launch { preferencesRepository.removeExcludedLine(line) }
    }

    fun addPreferredLine(line: String) {
        viewModelScope.launch { preferencesRepository.addPreferredLine(line) }
    }

    fun removePreferredLine(line: String) {
        viewModelScope.launch { preferencesRepository.removePreferredLine(line) }
    }

    fun toggleExcludedZone(zone: String) {
        viewModelScope.launch { preferencesRepository.toggleExcludedZone(zone) }
    }

    fun setLowFloorOnly(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setLowFloorOnly(enabled) }
    }

    fun setMinTransferMinutes(minutes: Int) {
        viewModelScope.launch { preferencesRepository.setMinTransferMinutes(minutes) }
    }

    fun setUlgowyTicket(isUlgowy: Boolean) {
        viewModelScope.launch { preferencesRepository.setUlgowyTicket(isUlgowy) }
    }

    fun setWalkingPace(pace: WalkingPace) {
        viewModelScope.launch { preferencesRepository.setWalkingPace(pace) }
    }

    fun setMaxOriginWalkMeters(meters: Int) {
        viewModelScope.launch { preferencesRepository.setMaxOriginWalkMeters(meters) }
    }

    fun setRealtimePollIntervalSec(seconds: Int) {
        viewModelScope.launch { preferencesRepository.setRealtimePollIntervalSec(seconds) }
    }

    fun setAutoUpdateSchedule(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.setAutoUpdateSchedule(enabled) }
    }

    fun setShowMapLandmarks(show: Boolean) {
        viewModelScope.launch { preferencesRepository.setShowMapLandmarks(show) }
    }

    fun rebuildGraphCache() {
        viewModelScope.launch {
            _storageStatus.update { it.copy(isRebuildingCache = true, cacheRebuiltNotice = false) }
            runCatching { transitGraphRepository.clearGraphCachesAndReload() }
            refreshStorageInfo()
            _storageStatus.update { it.copy(isRebuildingCache = false, cacheRebuiltNotice = true) }
        }
    }

    fun resetTripSettings() {
        viewModelScope.launch { preferencesRepository.resetTripSettings() }
    }
}
