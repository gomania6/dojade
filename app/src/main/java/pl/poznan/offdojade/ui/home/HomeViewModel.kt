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
import pl.poznan.offdojade.data.local.RecentStopEntry
import pl.poznan.offdojade.data.local.TripHistoryEntry
import pl.poznan.offdojade.data.location.AddressSearchProvider
import pl.poznan.offdojade.data.location.LocationProvider
import pl.poznan.offdojade.data.location.UserLocation
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.data.realtime.RealtimeStatus
import pl.poznan.offdojade.domain.model.AddressResult
import pl.poznan.offdojade.domain.model.NearbyStopBoard
import pl.poznan.offdojade.domain.model.PlannedTime
import pl.poznan.offdojade.domain.model.RouteEndpoint
import pl.poznan.offdojade.domain.model.RouteSearchRequest
import pl.poznan.offdojade.domain.model.StopGroup
import pl.poznan.offdojade.domain.model.TimeQueryMode
import pl.poznan.offdojade.domain.routing.RaptorData
import pl.poznan.offdojade.domain.routing.RaptorEngine
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import java.time.LocalTime
import java.time.ZoneId

enum class ActiveStopField {
    FROM,
    TO
}

data class HomeUiState(
    /** Выбранный Пункт А (`null` = текущее GPS-местоположение) */
    val fromEndpoint: RouteEndpoint? = null,
    val fromQuery: String = "",
    /** Выбранный Пункт Б (остановка или адрес) */
    val toEndpoint: RouteEndpoint? = null,
    val toQuery: String = "",
    val activeField: ActiveStopField? = null,
    val searchResults: List<StopGroup> = emptyList(),
    val addressResults: List<AddressResult> = emptyList(),
    val isSearchingAddresses: Boolean = false,
    val plannedTime: PlannedTime = PlannedTime(),
    val hasActiveFilters: Boolean = false,

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
    private val preferencesRepository: PreferencesRepository,
    private val addressSearchProvider: AddressSearchProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val realtimeStatus: StateFlow<RealtimeStatus> = realtimeRepository.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RealtimeStatus.Offline())

    val quickDestinations: StateFlow<List<QuickDestination>> = preferencesRepository.quickDestinations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteStopIds: StateFlow<Set<String>> = preferencesRepository.favoriteStopIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val tripHistory: StateFlow<List<TripHistoryEntry>> = preferencesRepository.tripHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentStops: StateFlow<List<RecentStopEntry>> = preferencesRepository.recentStops
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var searchJob: Job? = null
    private var addressSearchJob: Job? = null
    private var slotSearchJob: Job? = null

    init {
        // Восстанавливаем последний активный маршрут (чтобы при возврате с результатов можно было сразу его изменить)
        transitGraphRepository.activeSearchRequest.value?.let { req ->
            _uiState.update {
                it.copy(
                    fromEndpoint = req.from,
                    fromQuery = req.from?.displayName.orEmpty(),
                    toEndpoint = req.to,
                    toQuery = req.to.displayName,
                    plannedTime = req.plannedTime
                )
            }
        }
        refreshDashboard()
        viewModelScope.launch {
            realtimeRepository.status.collect {
                recomputeNearbyBoards()
            }
        }
        viewModelScope.launch {
            preferencesRepository.appSettings.collect { settings ->
                _uiState.update { it.copy(hasActiveFilters = settings.tripSettings.hasActiveFilters) }
            }
        }
    }

    fun setPlannedTime(plannedTime: PlannedTime) {
        _uiState.update { it.copy(plannedTime = plannedTime) }
    }

    fun resetPlannedTimeToNow() {
        _uiState.update { it.copy(plannedTime = PlannedTime(TimeQueryMode.DEPART_AT, null)) }
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

    /**
     * Вызывается при нажатии / фокусе на поле поиска «Откуда» или «Куда» —
     * сразу открывает подсказки (избранные, ближайшие и подходящие остановки).
     */
    fun onFieldFocused(field: ActiveStopField) {
        val currentQuery = when (field) {
            ActiveStopField.FROM -> _uiState.value.fromQuery
            ActiveStopField.TO -> _uiState.value.toQuery
        }
        _uiState.update { it.copy(activeField = field) }
        triggerSearch(currentQuery, immediate = true)
    }

    fun dismissSuggestions() {
        _uiState.update {
            it.copy(
                activeField = null,
                searchResults = emptyList(),
                addressResults = emptyList()
            )
        }
    }

    fun onFromQueryChange(query: String) {
        _uiState.update {
            it.copy(
                fromQuery = query,
                fromEndpoint = if (query.isBlank()) null else it.fromEndpoint,
                activeField = ActiveStopField.FROM
            )
        }
        triggerSearch(query, immediate = false)
    }

    fun onToQueryChange(query: String) {
        _uiState.update {
            it.copy(
                toQuery = query,
                toEndpoint = if (query.isBlank()) null else it.toEndpoint,
                activeField = ActiveStopField.TO
            )
        }
        triggerSearch(query, immediate = false)
    }

    private fun excludedOppositeStopName(field: ActiveStopField?): String? {
        val opposite = when (field) {
            ActiveStopField.FROM -> _uiState.value.toEndpoint
            ActiveStopField.TO -> _uiState.value.fromEndpoint
            null -> null
        } ?: return null
        return RaptorData.normalizeStopName(opposite.displayName)
    }

    private fun triggerSearch(query: String, immediate: Boolean) {
        searchJob?.cancel()
        addressSearchJob?.cancel()

        val trimmed = query.trim()
        val excludedName = excludedOppositeStopName(_uiState.value.activeField)
        searchJob = viewModelScope.launch {
            if (!immediate && trimmed.length >= 2) {
                delay(40L)
            }
            val rawMatches = if (trimmed.length < 2) {
                buildDefaultSuggestions()
            } else {
                transitGraphRepository.searchStopGroups(trimmed, limit = 20)
            }
            val filtered = if (excludedName != null) {
                rawMatches.filterNot { RaptorData.normalizeStopName(it.displayName) == excludedName }
            } else {
                rawMatches
            }
            _uiState.update { it.copy(searchResults = filtered) }
        }

        if (trimmed.length >= 3) {
            _uiState.update { it.copy(isSearchingAddresses = true) }
            addressSearchJob = viewModelScope.launch {
                delay(180L)
                val addresses = addressSearchProvider.searchAddresses(trimmed, limit = 6)
                _uiState.update {
                    it.copy(
                        addressResults = addresses,
                        isSearchingAddresses = false
                    )
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    addressResults = emptyList(),
                    isSearchingAddresses = false
                )
            }
        }
    }

    private suspend fun buildDefaultSuggestions(): List<StopGroup> {
        val all = transitGraphRepository.searchStopGroups("", limit = 120)
        if (all.isEmpty()) return emptyList()

        val favIds = favoriteStopIds.value
        val nearbyNames = _uiState.value.nearbyBoards.map { it.stopName.lowercase() }.toSet()

        val favorites = all.filter { it.primaryStopId in favIds }
        val nearby = all.filter { it.displayName.lowercase() in nearbyNames && it.primaryStopId !in favIds }
        val rest = all.filter { it.primaryStopId !in favIds && it.displayName.lowercase() !in nearbyNames }

        return (favorites + nearby + rest).distinctBy { it.primaryStopId }.take(12)
    }

    fun selectFromStop(group: StopGroup) {
        val opposite = excludedOppositeStopName(ActiveStopField.FROM)
        if (opposite != null && RaptorData.normalizeStopName(group.displayName) == opposite) return
        recordRecentStop(group)
        _uiState.update {
            it.copy(
                fromEndpoint = RouteEndpoint.Stop(group),
                fromQuery = group.displayName,
                activeField = null,
                searchResults = emptyList(),
                addressResults = emptyList()
            )
        }
    }

    fun selectFromAddress(address: AddressResult) {
        _uiState.update {
            it.copy(
                fromEndpoint = RouteEndpoint.Address(address),
                fromQuery = address.displayName,
                activeField = null,
                searchResults = emptyList(),
                addressResults = emptyList()
            )
        }
    }

    fun resetFromToGps() {
        _uiState.update {
            it.copy(
                fromEndpoint = null,
                fromQuery = "",
                activeField = null,
                searchResults = emptyList(),
                addressResults = emptyList()
            )
        }
    }

    fun selectToStop(group: StopGroup): Boolean {
        val opposite = excludedOppositeStopName(ActiveStopField.TO)
        if (opposite != null && RaptorData.normalizeStopName(group.displayName) == opposite) return false
        recordRecentStop(group)
        _uiState.update {
            it.copy(
                toEndpoint = RouteEndpoint.Stop(group),
                toQuery = group.displayName,
                activeField = null,
                searchResults = emptyList(),
                addressResults = emptyList()
            )
        }
        return true
    }

    fun selectToAddress(address: AddressResult) {
        _uiState.update {
            it.copy(
                toEndpoint = RouteEndpoint.Address(address),
                toQuery = address.displayName,
                activeField = null,
                searchResults = emptyList(),
                addressResults = emptyList()
            )
        }
    }

    fun clearToStop() {
        _uiState.update {
            it.copy(
                toEndpoint = null,
                toQuery = "",
                activeField = null,
                searchResults = emptyList(),
                addressResults = emptyList()
            )
        }
    }

    fun swapFromAndTo() {
        _uiState.update { state ->
            val oldFrom = state.fromEndpoint
            val oldTo = state.toEndpoint
            state.copy(
                fromEndpoint = oldTo,
                fromQuery = oldTo?.displayName.orEmpty(),
                toEndpoint = oldFrom,
                toQuery = oldFrom?.displayName.orEmpty(),
                activeField = null,
                searchResults = emptyList(),
                addressResults = emptyList()
            )
        }
    }

    fun prepareRouteSearch(to: RouteEndpoint) {
        val from = _uiState.value.fromEndpoint
        _uiState.update {
            it.copy(
                fromEndpoint = from,
                fromQuery = from?.displayName.orEmpty(),
                toEndpoint = to,
                toQuery = to.displayName,
                activeField = null,
                searchResults = emptyList(),
                addressResults = emptyList()
            )
        }
        transitGraphRepository.setActiveSearchRequest(
            RouteSearchRequest(
                from = from,
                to = to,
                plannedTime = _uiState.value.plannedTime
            )
        )
        recordTripHistory(from, to)
    }

    fun prepareRouteFromHistory(entry: TripHistoryEntry) {
        val from: RouteEndpoint? = when {
            !entry.fromStopId.isNullOrBlank() && !entry.fromName.isNullOrBlank() ->
                RouteEndpoint.Stop(
                    StopGroup(
                        displayName = entry.fromName,
                        primaryStopId = entry.fromStopId,
                        stopIds = listOf(entry.fromStopId),
                        zones = listOf("A"),
                        platformCount = 1,
                        latitude = entry.fromLat ?: 0.0,
                        longitude = entry.fromLon ?: 0.0
                    )
                )
            !entry.fromName.isNullOrBlank() && entry.fromLat != null && entry.fromLon != null ->
                RouteEndpoint.Address(
                    AddressResult(
                        id = "hist_from_${entry.id}",
                        displayName = entry.fromName,
                        subtitle = "Poznań",
                        latitude = entry.fromLat,
                        longitude = entry.fromLon
                    )
                )
            else -> null
        }

        val to: RouteEndpoint = if (!entry.toStopId.isNullOrBlank()) {
            RouteEndpoint.Stop(
                StopGroup(
                    displayName = entry.toName,
                    primaryStopId = entry.toStopId,
                    stopIds = listOf(entry.toStopId),
                    zones = listOf("A"),
                    platformCount = 1,
                    latitude = entry.toLat,
                    longitude = entry.toLon
                )
            )
        } else {
            RouteEndpoint.Address(
                AddressResult(
                    id = "hist_to_${entry.id}",
                    displayName = entry.toName,
                    subtitle = "Poznań",
                    latitude = entry.toLat,
                    longitude = entry.toLon
                )
            )
        }

        _uiState.update {
            it.copy(
                fromEndpoint = from,
                fromQuery = from?.displayName.orEmpty(),
                toEndpoint = to,
                toQuery = to.displayName,
                activeField = null,
                searchResults = emptyList(),
                addressResults = emptyList()
            )
        }
        transitGraphRepository.setActiveSearchRequest(
            RouteSearchRequest(
                from = from,
                to = to,
                plannedTime = _uiState.value.plannedTime
            )
        )
        recordTripHistory(from, to)
    }

    fun removeTripHistory(id: String) {
        viewModelScope.launch { preferencesRepository.removeTripHistory(id) }
    }

    fun clearTripHistory() {
        viewModelScope.launch { preferencesRepository.clearTripHistory() }
    }

    fun clearRecentStops() {
        viewModelScope.launch { preferencesRepository.clearRecentStops() }
    }

    private fun recordRecentStop(group: StopGroup) {
        viewModelScope.launch {
            preferencesRepository.addRecentStop(
                RecentStopEntry(
                    stopId = group.primaryStopId,
                    displayName = group.displayName,
                    zones = group.zones,
                    platformCount = group.platformCount,
                    latitude = group.latitude,
                    longitude = group.longitude
                )
            )
        }
    }

    private fun recordTripHistory(from: RouteEndpoint?, to: RouteEndpoint) {
        val fromStopId = (from as? RouteEndpoint.Stop)?.group?.primaryStopId
        val toStopId = (to as? RouteEndpoint.Stop)?.group?.primaryStopId
        val key = "${fromStopId ?: from?.displayName ?: "GPS"}->${toStopId ?: to.displayName}"
        viewModelScope.launch {
            preferencesRepository.addTripHistory(
                TripHistoryEntry(
                    id = key,
                    fromStopId = fromStopId,
                    fromName = from?.displayName,
                    fromLat = from?.latitude,
                    fromLon = from?.longitude,
                    toStopId = toStopId,
                    toName = to.displayName,
                    toLat = to.latitude,
                    toLon = to.longitude,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
    }

    fun hasLocationPermission(): Boolean = locationProvider.hasLocationPermission()

    fun isLocationEnabled(): Boolean = locationProvider.isLocationEnabled()

    /**
     * Быстрый маршрут: включает/обновляет свежую GPS-геолокацию и строит маршрут
     * от ближайшей подходящей остановки к текущему местоположению пользователя.
     */
    suspend fun prepareQuickRouteFromGps(stopId: String, stopName: String) {
        val freshLoc = locationProvider.getFreshOrFallbackLocation()
        val group = StopGroup(
            displayName = stopName,
            primaryStopId = stopId,
            stopIds = listOf(stopId),
            zones = listOf("A"),
            platformCount = 1,
            latitude = 0.0,
            longitude = 0.0
        )
        val target = RouteEndpoint.Stop(group)
        _uiState.update {
            it.copy(
                userLocation = freshLoc,
                fromEndpoint = null,
                fromQuery = "",
                toEndpoint = target,
                toQuery = stopName,
                activeField = null
            )
        }
        transitGraphRepository.setActiveSearchRequest(
            RouteSearchRequest(
                from = null,
                to = target,
                plannedTime = _uiState.value.plannedTime
            )
        )
        recordTripHistory(null, target)
    }

    fun prepareRouteSearchToStopId(stopId: String, stopName: String) {
        val group = StopGroup(
            displayName = stopName,
            primaryStopId = stopId,
            stopIds = listOf(stopId),
            zones = listOf("A"),
            platformCount = 1,
            latitude = 0.0,
            longitude = 0.0
        )
        val target = RouteEndpoint.Stop(group)
        _uiState.update {
            it.copy(
                toEndpoint = target,
                toQuery = stopName,
                activeField = null
            )
        }
        prepareRouteSearch(target)
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
            delay(80L)
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

    suspend fun resolveQuickDestinationStop(dest: QuickDestination): Pair<String, String>? {
        if (!dest.stopId.isNullOrBlank()) {
            return dest.stopId to (dest.stopName ?: dest.stopId)
        }
        if (!dest.stopName.isNullOrBlank()) {
            val match = transitGraphRepository.searchStopGroups(dest.stopName, limit = 1).firstOrNull()
            if (match != null) {
                preferencesRepository.setQuickDestination(dest.slot, match.primaryStopId, match.displayName)
                return match.primaryStopId to match.displayName
            }
        }
        return null
    }

    private fun recomputeNearbyBoards() {
        val graph = transitGraphRepository.graph.value ?: return
        val loc = _uiState.value.userLocation ?: return
        val nowSec = LocalTime.now(ZoneId.of("Europe/Warsaw")).toSecondOfDay()
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
