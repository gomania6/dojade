package pl.poznan.offdojade.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.poznan.offdojade.data.local.PreferencesRepository
import pl.poznan.offdojade.data.local.QuickDestination
import pl.poznan.offdojade.data.local.QuickDestinationSlot
import pl.poznan.offdojade.data.location.LocationProvider
import pl.poznan.offdojade.data.location.UserLocation
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.data.realtime.RealtimeStatus
import pl.poznan.offdojade.domain.model.NearbyStopBoard
import pl.poznan.offdojade.domain.model.StopGroup
import pl.poznan.offdojade.domain.routing.RaptorEngine
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import java.time.LocalTime

enum class ActiveStopField {
    FROM,
    TO
}

data class HomeUiState(
    /** Выбранный Пункт А (`null` = текущее GPS-местоположение) */
    val fromStop: StopGroup? = null,
    val fromQuery: String = "",
    /** Выбранный Пункт Б */
    val toStop: StopGroup? = null,
    val toQuery: String = "",
    val activeField: ActiveStopField? = null,
    val searchResults: List<StopGroup> = emptyList(),

    val nearbyBoards: List<NearbyStopBoard> = emptyList(),
    val userLocation: UserLocation? = null,
    val isRefreshing: Boolean = false,
    val graphReady: Boolean = false,

    val editingSlot: QuickDestinationSlot? = null,
    val slotSearchQuery: String = "",
    val slotSearchResults: List<StopGroup> = emptyList()
)

class HomeViewModel(
    private val transitGraphRepository: TransitGraphRepository,
    private val raptorEngine: RaptorEngine,
    private val realtimeRepository: RealtimeRepository,
    private val locationProvider: LocationProvider,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val realtimeStatus: StateFlow<RealtimeStatus> = realtimeRepository.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RealtimeStatus.Offline())

    val quickDestinations: StateFlow<List<QuickDestination>> = preferencesRepository.quickDestinations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteStopIds: StateFlow<Set<String>> = preferencesRepository.favoriteStopIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private var searchJob: Job? = null
    private var slotSearchJob: Job? = null

    init {
        refreshDashboard()
        viewModelScope.launch {
            realtimeRepository.status.collect {
                recomputeNearbyBoards()
            }
        }
    }

    fun refreshDashboard() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val graph = transitGraphRepository.ensureLoadedForToday()
            val loc = locationProvider.getCurrentOrFallbackLocation()
            realtimeRepository.refreshOnce()
            _uiState.update {
                it.copy(
                    userLocation = loc,
                    graphReady = graph != null,
                    isRefreshing = false
                )
            }
            recomputeNearbyBoards()
        }
    }

    fun onFromQueryChange(query: String) {
        _uiState.update {
            it.copy(
                fromQuery = query,
                fromStop = if (query.isBlank()) null else it.fromStop,
                activeField = ActiveStopField.FROM
            )
        }
        triggerSearch(query)
    }

    fun onToQueryChange(query: String) {
        _uiState.update {
            it.copy(
                toQuery = query,
                toStop = if (query.isBlank()) null else it.toStop,
                activeField = ActiveStopField.TO
            )
        }
        triggerSearch(query)
    }

    private fun triggerSearch(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(120L)
            val matches = transitGraphRepository.searchStopGroups(query, limit = 20)
            _uiState.update { it.copy(searchResults = matches) }
        }
    }

    fun selectFromStop(group: StopGroup) {
        _uiState.update {
            it.copy(
                fromStop = group,
                fromQuery = group.displayName,
                activeField = null,
                searchResults = emptyList()
            )
        }
    }

    fun resetFromToGps() {
        _uiState.update {
            it.copy(
                fromStop = null,
                fromQuery = "",
                activeField = null,
                searchResults = emptyList()
            )
        }
    }

    fun selectToStop(group: StopGroup) {
        _uiState.update {
            it.copy(
                toStop = group,
                toQuery = group.displayName,
                activeField = null,
                searchResults = emptyList()
            )
        }
    }

    fun clearToStop() {
        _uiState.update {
            it.copy(
                toStop = null,
                toQuery = "",
                activeField = null,
                searchResults = emptyList()
            )
        }
    }

    fun swapFromAndTo() {
        _uiState.update { state ->
            val oldFrom = state.fromStop
            val oldTo = state.toStop
            state.copy(
                fromStop = oldTo,
                fromQuery = oldTo?.displayName.orEmpty(),
                toStop = oldFrom,
                toQuery = oldFrom?.displayName.orEmpty(),
                activeField = null,
                searchResults = emptyList()
            )
        }
    }

    fun toggleFavoriteStop(stopId: String) {
        viewModelScope.launch {
            preferencesRepository.toggleFavoriteStop(stopId)
        }
    }

    fun openQuickDestinationEditor(slot: QuickDestinationSlot) {
        _uiState.update { it.copy(editingSlot = slot, slotSearchQuery = "", slotSearchResults = emptyList()) }
        viewModelScope.launch {
            val initial = transitGraphRepository.searchStopGroups("", limit = 20)
            _uiState.update { it.copy(slotSearchResults = initial) }
        }
    }

    fun closeQuickDestinationEditor() {
        _uiState.update { it.copy(editingSlot = null, slotSearchQuery = "", slotSearchResults = emptyList()) }
    }

    fun onSlotSearchQueryChange(query: String) {
        _uiState.update { it.copy(slotSearchQuery = query) }
        slotSearchJob?.cancel()
        slotSearchJob = viewModelScope.launch {
            delay(100L)
            val results = transitGraphRepository.searchStopGroups(query, limit = 20)
            _uiState.update { it.copy(slotSearchResults = results) }
        }
    }

    fun assignQuickDestination(slot: QuickDestinationSlot, group: StopGroup) {
        viewModelScope.launch {
            preferencesRepository.setQuickDestination(slot, group.primaryStopId, group.displayName)
            closeQuickDestinationEditor()
        }
    }

    suspend fun resolveQuickDestinationStopId(dest: QuickDestination): String? {
        if (!dest.stopId.isNullOrBlank()) return dest.stopId
        if (!dest.stopName.isNullOrBlank()) {
            val match = transitGraphRepository.searchStopGroups(dest.stopName, limit = 1).firstOrNull()
            if (match != null) {
                preferencesRepository.setQuickDestination(dest.slot, match.primaryStopId, match.displayName)
                return match.primaryStopId
            }
        }
        return null
    }

    private fun recomputeNearbyBoards() {
        val graph = transitGraphRepository.graph.value ?: return
        val loc = _uiState.value.userLocation ?: return
        val nowSec = LocalTime.now().toSecondOfDay()
        val boards = raptorEngine.findNearbyStopBoards(
            data = graph,
            userLat = loc.latitude,
            userLon = loc.longitude,
            currentTimeSec = nowSec,
            tripDelays = realtimeRepository.tripDelaysSec,
            maxStops = 3,
            departuresPerStop = 4
        )
        _uiState.update { it.copy(nearbyBoards = boards, graphReady = true) }
    }
}
