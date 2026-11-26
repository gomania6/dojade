package pl.poznan.offdojade.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.local.PreferencesRepository
import pl.poznan.offdojade.data.location.LocationProvider
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.domain.model.RouteEndpoint
import pl.poznan.offdojade.domain.model.RouteSearchRequest
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.domain.model.TripSettings
import pl.poznan.offdojade.domain.routing.RaptorEngine
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import java.time.LocalTime

enum class RouteSearchError {
    SCHEDULE_NOT_LOADED,
    NO_ROUTES_FOUND
}

data class RouteResultsUiState(
    val fromStopName: String? = null,
    val targetStopName: String = "",
    val isLoading: Boolean = true,
    val isLoadingEarlier: Boolean = false,
    val isLoadingLater: Boolean = false,
    val noMoreEarlier: Boolean = false,
    val noMoreLater: Boolean = false,
    val referenceNowSec: Int = LocalTime.now().toSecondOfDay(),
    val journeys: List<TransitJourney> = emptyList(),
    val searchDurationMs: Long = 0L,
    val error: RouteSearchError? = null,
    val hasActiveFilters: Boolean = false
)

class RouteResultsViewModel(
    private val transitGraphRepository: TransitGraphRepository,
    private val raptorEngine: RaptorEngine,
    private val realtimeRepository: RealtimeRepository,
    private val locationProvider: LocationProvider,
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouteResultsUiState())
    val uiState: StateFlow<RouteResultsUiState> = _uiState.asStateFlow()

    private var cachedOriginLat: Double = AppConfig.POZNAN_CENTER_LAT
    private var cachedOriginLon: Double = AppConfig.POZNAN_CENTER_LON
    private var currentTripSettings: TripSettings = TripSettings()
    private var activeRequest: RouteSearchRequest? = null

    init {
        viewModelScope.launch {
            preferencesRepository.appSettings
                .map { it.tripSettings }
                .distinctUntilChanged()
                .collect { tripSettings ->
                    val changed = tripSettings != currentTripSettings
                    currentTripSettings = tripSettings
                    _uiState.update { it.copy(hasActiveFilters = tripSettings.hasActiveFilters) }
                    if (changed && activeRequest != null) {
                        searchActiveRequest()
                    }
                }
        }
    }

    fun searchActiveRequest() {
        val request = transitGraphRepository.activeSearchRequest.value ?: activeRequest ?: return
        activeRequest = request

        viewModelScope.launch {
            val nowSec = LocalTime.now().toSecondOfDay()
            val settings = preferencesRepository.getAppSettings().tripSettings
            currentTripSettings = settings

            _uiState.update {
                it.copy(
                    fromStopName = request.from?.displayName,
                    targetStopName = request.to.displayName,
                    isLoading = true,
                    isLoadingEarlier = false,
                    isLoadingLater = false,
                    noMoreEarlier = false,
                    noMoreLater = false,
                    referenceNowSec = nowSec,
                    error = null,
                    hasActiveFilters = settings.hasActiveFilters
                )
            }

            val graph = transitGraphRepository.ensureLoadedForToday()
            if (graph == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        journeys = emptyList(),
                        error = RouteSearchError.SCHEDULE_NOT_LOADED
                    )
                }
                return@launch
            }

            when (val from = request.from) {
                null -> {
                    val userLocation = locationProvider.getCurrentOrFallbackLocation()
                    cachedOriginLat = userLocation.latitude
                    cachedOriginLon = userLocation.longitude
                }
                is RouteEndpoint.Address -> {
                    cachedOriginLat = from.latitude
                    cachedOriginLon = from.longitude
                }
                is RouteEndpoint.Stop -> {
                    cachedOriginLat = from.latitude
                    cachedOriginLon = from.longitude
                }
            }

            val originStopId = (request.from as? RouteEndpoint.Stop)?.group?.primaryStopId
            val originName = request.from?.displayName ?: "GPS"
            val targetStopId = (request.to as? RouteEndpoint.Stop)?.group?.primaryStopId
            val targetAddr = request.to as? RouteEndpoint.Address

            val results = withContext(Dispatchers.Default) {
                raptorEngine.searchUpcomingJourneys(
                    data = graph,
                    originStopId = originStopId,
                    originLat = cachedOriginLat,
                    originLon = cachedOriginLon,
                    targetStopId = targetStopId,
                    departureTimeSec = nowSec,
                    tripDelays = realtimeRepository.tripDelaysSec,
                    tripSettings = settings,
                    limit = 5,
                    originName = originName,
                    targetLat = targetAddr?.latitude,
                    targetLon = targetAddr?.longitude,
                    targetName = targetAddr?.displayName
                )
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    journeys = results,
                    searchDurationMs = results.firstOrNull()?.searchDurationMs ?: 0L,
                    error = if (results.isEmpty()) RouteSearchError.NO_ROUTES_FOUND else null
                )
            }
        }
    }

    /**
     * Подгрузка уже отъехавших рейсов при прокрутке/нажатии вверх.
     */
    fun loadEarlierJourneys() {
        val current = _uiState.value
        val request = activeRequest ?: return
        if (current.isLoading || current.isLoadingEarlier || current.noMoreEarlier) return
        val graph = transitGraphRepository.graph.value ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingEarlier = true) }
            val anchorSec = current.journeys.minOfOrNull { it.departureTimeSec } ?: current.referenceNowSec
            val existingIds = current.journeys.map { it.id }.toSet()

            val originStopId = (request.from as? RouteEndpoint.Stop)?.group?.primaryStopId
            val originName = request.from?.displayName ?: "GPS"
            val targetStopId = (request.to as? RouteEndpoint.Stop)?.group?.primaryStopId
            val targetAddr = request.to as? RouteEndpoint.Address

            val earlier = withContext(Dispatchers.Default) {
                raptorEngine.searchEarlierJourneys(
                    data = graph,
                    originStopId = originStopId,
                    originLat = cachedOriginLat,
                    originLon = cachedOriginLon,
                    targetStopId = targetStopId,
                    beforeDepartureSec = anchorSec,
                    tripDelays = realtimeRepository.tripDelaysSec,
                    tripSettings = currentTripSettings,
                    limit = 5,
                    excludeIds = existingIds,
                    originName = originName,
                    targetLat = targetAddr?.latitude,
                    targetLon = targetAddr?.longitude,
                    targetName = targetAddr?.displayName
                )
            }

            _uiState.update { state ->
                val merged = (earlier + state.journeys).distinctBy { it.id }
                state.copy(
                    isLoadingEarlier = false,
                    noMoreEarlier = earlier.isEmpty(),
                    journeys = merged
                )
            }
        }
    }

    /**
     * Подгрузка следующих (более поздних) 5 рейсов при прокрутке/нажатии вниз.
     */
    fun loadLaterJourneys() {
        val current = _uiState.value
        val request = activeRequest ?: return
        if (current.isLoading || current.isLoadingLater || current.noMoreLater) return
        val graph = transitGraphRepository.graph.value ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLater = true) }
            val lastDepSec = current.journeys.maxOfOrNull { it.departureTimeSec } ?: current.referenceNowSec
            val existingIds = current.journeys.map { it.id }.toSet()

            val originStopId = (request.from as? RouteEndpoint.Stop)?.group?.primaryStopId
            val originName = request.from?.displayName ?: "GPS"
            val targetStopId = (request.to as? RouteEndpoint.Stop)?.group?.primaryStopId
            val targetAddr = request.to as? RouteEndpoint.Address

            val later = withContext(Dispatchers.Default) {
                raptorEngine.searchUpcomingJourneys(
                    data = graph,
                    originStopId = originStopId,
                    originLat = cachedOriginLat,
                    originLon = cachedOriginLon,
                    targetStopId = targetStopId,
                    departureTimeSec = lastDepSec + 60,
                    tripDelays = realtimeRepository.tripDelaysSec,
                    tripSettings = currentTripSettings,
                    limit = 5,
                    excludeIds = existingIds,
                    originName = originName,
                    targetLat = targetAddr?.latitude,
                    targetLon = targetAddr?.longitude,
                    targetName = targetAddr?.displayName
                )
            }

            _uiState.update { state ->
                val merged = (state.journeys + later).distinctBy { it.id }
                state.copy(
                    isLoadingLater = false,
                    noMoreLater = later.isEmpty(),
                    journeys = merged
                )
            }
        }
    }

    fun selectJourney(journey: TransitJourney) {
        transitGraphRepository.selectJourney(journey)
    }
}
