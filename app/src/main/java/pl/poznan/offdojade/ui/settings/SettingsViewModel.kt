package pl.poznan.offdojade.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.poznan.offdojade.data.local.PreferencesRepository
import pl.poznan.offdojade.domain.model.AppLanguage
import pl.poznan.offdojade.domain.model.AppSettings
import pl.poznan.offdojade.domain.model.RoutePreference
import pl.poznan.offdojade.domain.model.ThemeMode
import pl.poznan.offdojade.domain.model.TransportMode

class SettingsViewModel(
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = preferencesRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

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

    fun resetTripSettings() {
        viewModelScope.launch { preferencesRepository.resetTripSettings() }
    }
}
