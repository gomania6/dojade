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
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.RouteEndpoint
import pl.poznan.offdojade.domain.model.RouteSearchRequest
import pl.poznan.offdojade.domain.model.TimeQueryMode
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.domain.model.TripSettings
import pl.poznan.offdojade.domain.routing.RaptorData
import pl.poznan.offdojade.domain.routing.RaptorEngine
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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
    val referenceNowSec: Int = LocalTime.now(ZoneId.of("Europe/Warsaw")).toSecondOfDay(),
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
    private var loadedDayOffset: Int = 0
    private var maxDayOffset: Int = 2
    private var activeDayGraph: RaptorData? = null

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
            val nowSec = LocalTime.now(ZoneId.of("Europe/Warsaw")).toSecondOfDay()
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

            val today = LocalDate.now(ZoneId.of("Europe/Warsaw"))
            val planned = request.plannedTime
            val targetDate = planned.resolveDate(today)
            val initialDayOffset = ChronoUnit.DAYS.between(today, targetDate).toInt().coerceAtLeast(0)

            val graph = if (initialDayOffset == 0) {
                transitGraphRepository.ensureLoadedForToday()
            } else {
                transitGraphRepository.buildGraphForDate(targetDate)
            }
            loadedDayOffset = initialDayOffset
            maxDayOffset = initialDayOffset + 2
            activeDayGraph = graph
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

            val queryTimeSec = planned.timeSec ?: if (initialDayOffset == 0) nowSec else 8 * 3600
            val activeDelays = if (initialDayOffset == 0) realtimeRepository.tripDelaysSec else emptyMap()

            var results = withContext(Dispatchers.Default) {
                val raw = if (planned.mode == TimeQueryMode.ARRIVE_BY && planned.timeSec != null) {
                    raptorEngine.searchArrivingByJourneys(
                        data = graph,
                        originStopId = originStopId,
                        originLat = cachedOriginLat,
                        originLon = cachedOriginLon,
                        targetStopId = targetStopId,
                        arriveByTimeSec = planned.timeSec,
                        tripDelays = activeDelays,
                        tripSettings = settings,
                        limit = 5,
                        originName = originName,
                        targetLat = targetAddr?.latitude,
                        targetLon = targetAddr?.longitude,
                        targetName = targetAddr?.displayName
                    )
                } else {
                    raptorEngine.searchUpcomingJourneys(
                        data = graph,
                        originStopId = originStopId,
                        originLat = cachedOriginLat,
                        originLon = cachedOriginLon,
                        targetStopId = targetStopId,
                        departureTimeSec = queryTimeSec,
                        tripDelays = activeDelays,
                        tripSettings = settings,
                        limit = 5,
                        originName = originName,
                        targetLat = targetAddr?.latitude,
                        targetLon = targetAddr?.longitude,
                        targetName = targetAddr?.displayName
                    )
                }
                raw.map { shiftJourneyByDays(it, initialDayOffset) }
            }

            // Если на выбранный день рейсов уже нет (например, поздно ночью) — сразу подгружаем рейсы на следующий день с 00:00
            if (results.isEmpty() && planned.mode == TimeQueryMode.DEPART_AT) {
                val nextOffset = initialDayOffset + 1
                val nextDate = today.plusDays(nextOffset.toLong())
                val nextGraph = transitGraphRepository.buildGraphForDate(nextDate)
                if (nextGraph != null) {
                    loadedDayOffset = nextOffset
                    activeDayGraph = nextGraph
                    results = withContext(Dispatchers.Default) {
                        raptorEngine.searchUpcomingJourneys(
                            data = nextGraph,
                            originStopId = originStopId,
                            originLat = cachedOriginLat,
                            originLon = cachedOriginLon,
                            targetStopId = targetStopId,
                            departureTimeSec = 0,
                            tripDelays = emptyMap(),
                            tripSettings = settings,
                            limit = 5,
                            originName = originName,
                            targetLat = targetAddr?.latitude,
                            targetLon = targetAddr?.longitude,
                            targetName = targetAddr?.displayName
                        ).map { shiftJourneyByDays(it, nextOffset) }
                    }
                }
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
     * Если на сегодня рейсы закончились — автоматически загружает расписание следующего дня.
     */
    fun loadLaterJourneys() {
        val current = _uiState.value
        val request = activeRequest ?: return
        if (current.isLoading || current.isLoadingLater || current.noMoreLater) return
        val currentGraph = activeDayGraph ?: transitGraphRepository.graph.value ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingLater = true) }
            val lastDepSec = current.journeys.maxOfOrNull { it.departureTimeSec } ?: current.referenceNowSec
            val existingIds = current.journeys.map { it.id }.toSet()

            val originStopId = (request.from as? RouteEndpoint.Stop)?.group?.primaryStopId
            val originName = request.from?.displayName ?: "GPS"
            val targetStopId = (request.to as? RouteEndpoint.Stop)?.group?.primaryStopId
            val targetAddr = request.to as? RouteEndpoint.Address

            val dayShiftSec = loadedDayOffset * 86_400
            val localStartSec = (lastDepSec - dayShiftSec + 60).coerceAtLeast(0)
            val delaysForDay = if (loadedDayOffset == 0) realtimeRepository.tripDelaysSec else emptyMap()

            var later = withContext(Dispatchers.Default) {
                raptorEngine.searchUpcomingJourneys(
                    data = currentGraph,
                    originStopId = originStopId,
                    originLat = cachedOriginLat,
                    originLon = cachedOriginLon,
                    targetStopId = targetStopId,
                    departureTimeSec = localStartSec,
                    tripDelays = delaysForDay,
                    tripSettings = currentTripSettings,
                    limit = 5,
                    excludeIds = existingIds,
                    originName = originName,
                    targetLat = targetAddr?.latitude,
                    targetLon = targetAddr?.longitude,
                    targetName = targetAddr?.displayName
                ).map { shiftJourneyByDays(it, loadedDayOffset) }
            }

            // Если рейсы на текущий день закончились — переходим к следующему дню (с 00:00)
            if (later.isEmpty() && loadedDayOffset < maxDayOffset) {
                val nextOffset = loadedDayOffset + 1
                val nextDate = LocalDate.now(ZoneId.of("Europe/Warsaw")).plusDays(nextOffset.toLong())
                val nextGraph = transitGraphRepository.buildGraphForDate(nextDate)
                if (nextGraph != null) {
                    loadedDayOffset = nextOffset
                    activeDayGraph = nextGraph
                    later = withContext(Dispatchers.Default) {
                        raptorEngine.searchUpcomingJourneys(
                            data = nextGraph,
                            originStopId = originStopId,
                            originLat = cachedOriginLat,
                            originLon = cachedOriginLon,
                            targetStopId = targetStopId,
                            departureTimeSec = 0,
                            tripDelays = emptyMap(),
                            tripSettings = currentTripSettings,
                            limit = 5,
                            excludeIds = existingIds,
                            originName = originName,
                            targetLat = targetAddr?.latitude,
                            targetLon = targetAddr?.longitude,
                            targetName = targetAddr?.displayName
                        ).map { shiftJourneyByDays(it, nextOffset) }
                    }
                }
            }

            _uiState.update { state ->
                val merged = (state.journeys + later).distinctBy { it.id }
                state.copy(
                    isLoadingLater = false,
                    noMoreLater = later.isEmpty() && loadedDayOffset >= maxDayOffset,
                    journeys = merged
                )
            }
        }
    }

    private fun shiftJourneyByDays(journey: TransitJourney, dayOffset: Int): TransitJourney {
        if (dayOffset <= 0) return journey
        val shift = dayOffset * 86_400
        return journey.copy(
            id = "d${dayOffset}_${journey.id}",
            departureTimeSec = journey.departureTimeSec + shift,
            arrivalTimeSec = journey.arrivalTimeSec + shift,
            legs = journey.legs.map { leg ->
                when (leg) {
                    is JourneyLeg.WalkLeg -> leg.copy(
                        startTimeSec = leg.startTimeSec + shift,
                        endTimeSec = leg.endTimeSec + shift
                    )
                    is JourneyLeg.TransitLeg -> leg.copy(
                        startTimeSec = leg.startTimeSec + shift,
                        endTimeSec = leg.endTimeSec + shift,
                        stops = leg.stops.map { stop ->
                            stop.copy(
                                scheduledTimeSec = stop.scheduledTimeSec + shift,
                                effectiveTimeSec = stop.effectiveTimeSec + shift
                            )
                        }
                    )
                }
            }
        )
    }

    fun selectJourney(journey: TransitJourney) {
        transitGraphRepository.selectJourney(journey)
    }
}
