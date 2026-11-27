package pl.poznan.offdojade.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.gtfs.GeoUtils
import pl.poznan.offdojade.data.local.PreferencesRepository
import pl.poznan.offdojade.data.location.LocationProvider
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.data.realtime.RealtimeStatus
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.RouteEndpoint
import pl.poznan.offdojade.domain.model.RouteSearchRequest
import pl.poznan.offdojade.domain.model.TimeQueryMode
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.domain.model.TripSettings
import pl.poznan.offdojade.domain.routing.CachedRouteResults
import pl.poznan.offdojade.domain.routing.RaptorData
import pl.poznan.offdojade.domain.routing.RaptorEngine
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class RouteSearchError {
    SCHEDULE_NOT_LOADED,
    NO_ROUTES_FOUND,
    TOO_CLOSE_WALK
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
    val directWalkDistanceMeters: Int = 0,
    val directWalkMinutes: Int = 0,
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

    val realtimeStatus: StateFlow<RealtimeStatus> = realtimeRepository.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RealtimeStatus.Offline())

    /** Карта всех отслеживаемых рейсов (`tripId` и его суффикс без префикса сервиса -> `liveDelaySec`) */
    val liveTripDelays: StateFlow<Map<String, Int>> = realtimeRepository.status
        .map {
            val vehicles = realtimeRepository.vehicles.value
            val delays = realtimeRepository.tripDelaysSec
            buildMap((vehicles.size + delays.size) * 2) {
                delays.forEach { (tripId, delay) ->
                    put(tripId, delay)
                    val core = tripId.substringAfter('_', "")
                    if (core.isNotEmpty() && core !in this) put(core, delay)
                }
                vehicles.forEach { v ->
                    val tid = v.tripId
                    if (!tid.isNullOrBlank()) {
                        if (tid !in this) put(tid, 0)
                        val core = tid.substringAfter('_', "")
                        if (core.isNotEmpty() && core !in this) put(core, 0)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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
                        searchActiveRequest(forceRefresh = true)
                    }
                }
        }
    }

    fun searchActiveRequest(forceRefresh: Boolean = false) {
        val request = transitGraphRepository.activeSearchRequest.value ?: activeRequest ?: return
        activeRequest = request

        viewModelScope.launch {
            val nowSec = LocalTime.now(ZoneId.of("Europe/Warsaw")).toSecondOfDay()
            val settings = preferencesRepository.getAppSettings().tripSettings
            currentTripSettings = settings

            if (!forceRefresh) {
                val cached = transitGraphRepository.cachedRouteResults
                if (cached != null && cached.request == request && cached.tripSettings == settings && cached.journeys.isNotEmpty()) {
                    cachedOriginLat = cached.originLat
                    cachedOriginLon = cached.originLon
                    loadedDayOffset = cached.loadedDayOffset
                    maxDayOffset = cached.maxDayOffset
                    val today = LocalDate.now(ZoneId.of("Europe/Warsaw"))
                    activeDayGraph = if (loadedDayOffset == 0) {
                        transitGraphRepository.ensureLoadedForToday()
                    } else {
                        transitGraphRepository.buildGraphForDate(today.plusDays(loadedDayOffset.toLong()))
                    }
                    _uiState.update {
                        it.copy(
                            fromStopName = request.from?.displayName,
                            targetStopName = request.to.displayName,
                            isLoading = false,
                            isLoadingEarlier = false,
                            isLoadingLater = false,
                            noMoreEarlier = cached.noMoreEarlier,
                            noMoreLater = cached.noMoreLater,
                            referenceNowSec = nowSec,
                            journeys = cached.journeys,
                            searchDurationMs = cached.searchDurationMs,
                            error = null,
                            hasActiveFilters = settings.hasActiveFilters
                        )
                    }
                    return@launch
                }
            }

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

            val (effOriginLat, effOriginLon) = resolveCoords(graph, originStopId, cachedOriginLat, cachedOriginLon)
            val (effTargetLat, effTargetLon) = resolveCoords(graph, targetStopId, request.to.latitude, request.to.longitude)
            val directDistMeters = if (effOriginLat != 0.0 && effTargetLat != 0.0) {
                GeoUtils.haversineMeters(effOriginLat, effOriginLon, effTargetLat, effTargetLon)
            } else {
                Double.MAX_VALUE
            }

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
                        limit = INITIAL_RESULTS_LIMIT,
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
                        limit = INITIAL_RESULTS_LIMIT,
                        originName = originName,
                        targetLat = targetAddr?.latitude,
                        targetLon = targetAddr?.longitude,
                        targetName = targetAddr?.displayName
                    )
                }
                raw.map { shiftJourneyByDays(it, initialDayOffset) }
            }

            // Если на выбранный день рейсов уже нет (например, поздно ночью) — сразу подгружаем рейсы на следующий день с 00:00
            // (но НЕ для сверхкоротких расстояний <= 700 м, где быстрее дойти пешком)
            if (results.isEmpty() && planned.mode == TimeQueryMode.DEPART_AT && directDistMeters > WALK_ONLY_THRESHOLD_METERS) {
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
                            limit = INITIAL_RESULTS_LIMIT,
                            originName = originName,
                            targetLat = targetAddr?.latitude,
                            targetLon = targetAddr?.longitude,
                            targetName = targetAddr?.displayName
                        ).map { shiftJourneyByDays(it, nextOffset) }
                    }
                }
            }

            val durationMs = results.firstOrNull()?.searchDurationMs ?: 0L
            val isTooClose = results.isEmpty() && directDistMeters <= WALK_ONLY_THRESHOLD_METERS
            val walkMetersEst = if (isTooClose) (directDistMeters * 1.2).toInt().coerceAtLeast(10) else 0
            val walkMinsEst = if (isTooClose) ((GeoUtils.walkingTimeSeconds(walkMetersEst.toDouble()) + 30) / 60).coerceAtLeast(1) else 0
            _uiState.update {
                it.copy(
                    isLoading = false,
                    journeys = results,
                    searchDurationMs = durationMs,
                    error = when {
                        results.isNotEmpty() -> null
                        isTooClose -> RouteSearchError.TOO_CLOSE_WALK
                        else -> RouteSearchError.NO_ROUTES_FOUND
                    },
                    directWalkDistanceMeters = walkMetersEst,
                    directWalkMinutes = walkMinsEst
                )
            }
            if (results.isNotEmpty()) {
                persistCache(request, settings, results, durationMs, nowSec, noMoreEarlier = false, noMoreLater = false)
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

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingEarlier = true) }
            val anchorSec = current.journeys.minOfOrNull { it.departureTimeSec } ?: current.referenceNowSec
            val earliestDayOffset = (anchorSec / 86_400).coerceAtLeast(0)
            val today = LocalDate.now(ZoneId.of("Europe/Warsaw"))
            val dayGraph = if (earliestDayOffset == 0) {
                transitGraphRepository.ensureLoadedForToday()
            } else {
                transitGraphRepository.buildGraphForDate(today.plusDays(earliestDayOffset.toLong()))
            }
            if (dayGraph == null) {
                _uiState.update { it.copy(isLoadingEarlier = false, noMoreEarlier = true) }
                return@launch
            }

            val dayShiftSec = earliestDayOffset * 86_400
            val localAnchorSec = (anchorSec - dayShiftSec).coerceAtLeast(0)
            val delaysForDay = if (earliestDayOffset == 0) realtimeRepository.tripDelaysSec else emptyMap()
            val existingLocalIds = unshiftedIdsForDay(current.journeys, earliestDayOffset)

            val originStopId = (request.from as? RouteEndpoint.Stop)?.group?.primaryStopId
            val originName = request.from?.displayName ?: "GPS"
            val targetStopId = (request.to as? RouteEndpoint.Stop)?.group?.primaryStopId
            val targetAddr = request.to as? RouteEndpoint.Address

            var earlier = withContext(Dispatchers.Default) {
                raptorEngine.searchEarlierJourneys(
                    data = dayGraph,
                    originStopId = originStopId,
                    originLat = cachedOriginLat,
                    originLon = cachedOriginLon,
                    targetStopId = targetStopId,
                    beforeDepartureSec = localAnchorSec,
                    tripDelays = delaysForDay,
                    tripSettings = currentTripSettings,
                    limit = 5,
                    excludeIds = existingLocalIds,
                    originName = originName,
                    targetLat = targetAddr?.latitude,
                    targetLon = targetAddr?.longitude,
                    targetName = targetAddr?.displayName
                ).map { shiftJourneyByDays(it, earliestDayOffset) }
            }

            // Если на этом дне раньше 00:00 рейсов нет, а это день > 0 — подгружаем конец предыдущего дня
            if (earlier.isEmpty() && earliestDayOffset > 0) {
                val prevOffset = earliestDayOffset - 1
                val prevGraph = if (prevOffset == 0) {
                    transitGraphRepository.ensureLoadedForToday()
                } else {
                    transitGraphRepository.buildGraphForDate(today.plusDays(prevOffset.toLong()))
                }
                if (prevGraph != null) {
                    val prevDelays = if (prevOffset == 0) realtimeRepository.tripDelaysSec else emptyMap()
                    val prevExistingIds = unshiftedIdsForDay(current.journeys, prevOffset)
                    earlier = withContext(Dispatchers.Default) {
                        raptorEngine.searchEarlierJourneys(
                            data = prevGraph,
                            originStopId = originStopId,
                            originLat = cachedOriginLat,
                            originLon = cachedOriginLon,
                            targetStopId = targetStopId,
                            beforeDepartureSec = 86_400,
                            tripDelays = prevDelays,
                            tripSettings = currentTripSettings,
                            limit = 5,
                            excludeIds = prevExistingIds,
                            originName = originName,
                            targetLat = targetAddr?.latitude,
                            targetLon = targetAddr?.longitude,
                            targetName = targetAddr?.displayName
                        ).map { shiftJourneyByDays(it, prevOffset) }
                    }
                }
            }

            _uiState.update { state ->
                val merged = (earlier + state.journeys).distinctBy { it.id }
                val noMore = earlier.isEmpty()
                persistCache(
                    request = request,
                    settings = currentTripSettings,
                    journeys = merged,
                    searchDurationMs = state.searchDurationMs,
                    referenceNowSec = state.referenceNowSec,
                    noMoreEarlier = noMore,
                    noMoreLater = state.noMoreLater
                )
                state.copy(
                    isLoadingEarlier = false,
                    noMoreEarlier = noMore,
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

            val originStopId = (request.from as? RouteEndpoint.Stop)?.group?.primaryStopId
            val originName = request.from?.displayName ?: "GPS"
            val targetStopId = (request.to as? RouteEndpoint.Stop)?.group?.primaryStopId
            val targetAddr = request.to as? RouteEndpoint.Address

            val dayShiftSec = loadedDayOffset * 86_400
            val localStartSec = (lastDepSec - dayShiftSec + 60).coerceAtLeast(0)
            val delaysForDay = if (loadedDayOffset == 0) realtimeRepository.tripDelaysSec else emptyMap()
            val existingLocalIds = unshiftedIdsForDay(current.journeys, loadedDayOffset)

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
                    excludeIds = existingLocalIds,
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
                    val nextExistingIds = unshiftedIdsForDay(current.journeys, nextOffset)
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
                            excludeIds = nextExistingIds,
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
                val noMore = later.isEmpty() && loadedDayOffset >= maxDayOffset
                persistCache(
                    request = request,
                    settings = currentTripSettings,
                    journeys = merged,
                    searchDurationMs = state.searchDurationMs,
                    referenceNowSec = state.referenceNowSec,
                    noMoreEarlier = state.noMoreEarlier,
                    noMoreLater = noMore
                )
                state.copy(
                    isLoadingLater = false,
                    noMoreLater = noMore,
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

    private fun persistCache(
        request: RouteSearchRequest,
        settings: TripSettings,
        journeys: List<TransitJourney>,
        searchDurationMs: Long,
        referenceNowSec: Int,
        noMoreEarlier: Boolean,
        noMoreLater: Boolean
    ) {
        transitGraphRepository.cachedRouteResults = CachedRouteResults(
            request = request,
            tripSettings = settings,
            journeys = journeys,
            searchDurationMs = searchDurationMs,
            referenceNowSec = referenceNowSec,
            loadedDayOffset = loadedDayOffset,
            maxDayOffset = maxDayOffset,
            noMoreEarlier = noMoreEarlier,
            noMoreLater = noMoreLater,
            originLat = cachedOriginLat,
            originLon = cachedOriginLon
        )
    }

    private fun resolveCoords(
        graph: RaptorData,
        stopId: String?,
        fallbackLat: Double,
        fallbackLon: Double
    ): Pair<Double, Double> {
        if (!stopId.isNullOrBlank()) {
            val idx = graph.stopIdToIndex[stopId]
            if (idx != null) {
                return graph.stopLats[idx] to graph.stopLons[idx]
            }
        }
        return fallbackLat to fallbackLon
    }

    private fun unshiftedIdsForDay(journeys: List<TransitJourney>, dayOffset: Int): Set<String> {
        if (dayOffset <= 0) return journeys.map { it.id }.toSet()
        val prefix = "d${dayOffset}_"
        return journeys.mapNotNull { j ->
            if (j.id.startsWith(prefix)) j.id.removePrefix(prefix) else null
        }.toSet()
    }

    companion object {
        private const val INITIAL_RESULTS_LIMIT = 8
        private const val WALK_ONLY_THRESHOLD_METERS = 700.0
    }
}
