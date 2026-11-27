package pl.poznan.offdojade.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.poznan.offdojade.data.gtfs.GeoUtils
import pl.poznan.offdojade.data.location.WalkingRouteProvider
import pl.poznan.offdojade.data.map.MapDownloader
import pl.poznan.offdojade.data.realtime.LiveVehicle
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.data.realtime.RealtimeStatus
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.domain.routing.RaptorData
import pl.poznan.offdojade.domain.routing.ShapeSlicer
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import pl.poznan.offdojade.domain.routing.TransitLegInsight
import pl.poznan.offdojade.domain.tariff.ZtmTicketCalculator
import java.io.File

/**
 * Мягкая палитра цветов для сегментов маршрута (ночные автобусы отображаются белым).
 */
object RouteLegColors {
    const val NIGHT_BUS_HEX = "#EAEFF5"
    const val RAIL_HEX = "#D32F2F"

    val HEX_PALETTE = listOf(
        "#3FA372", // 1-й сегмент: мягкий зелёный
        "#4B83C3", // 2-й сегмент: мягкий синий
        "#D98A4E", // 3-й сегмент: мягкий оранжевый
        "#9B72CF", // 4-й сегмент: мягкий фиолетовый
        "#CF6B9A", // 5-й сегмент: мягкий розовый
        "#47A8B8"  // 6-й сегмент: мягкий бирюзовый
    )

    fun hexForLeg(
        transitLegIndex: Int,
        shortName: String = "",
        isTram: Boolean = false,
        isRail: Boolean = false
    ): String = if (isRail) RAIL_HEX else HEX_PALETTE[transitLegIndex.coerceAtLeast(0) % HEX_PALETTE.size]
}

data class RouteShapePolyline(
    val legIndex: Int,
    val isTram: Boolean,
    val colorHex: String,
    val coordinates: List<Pair<Double, Double>>
)

data class RouteDetailsUiState(
    val journey: TransitJourney? = null,
    /** Активные участки маршрута транспорта (от посадки до высадки — полная непрозрачность) */
    val polylines: List<RouteShapePolyline> = emptyList(),
    /** Полупрозрачная трасса транспорта вне нужного маршрута (максимум до 1 ближайшей остановки) */
    val outsidePolylines: List<RouteShapePolyline> = emptyList(),
    /** Пешие участки (до остановки, пересадки, до адреса) — отображаются пунктирной линией */
    val walkPolylines: List<List<Pair<Double, Double>>> = emptyList(),
    /** Альтернативные линии и примерный интервал для каждого транзитного сегмента (ключ = tripId) */
    val legInsights: Map<String, TransitLegInsight> = emptyMap(),
    val localMapFile: File? = null
)

class RouteDetailsViewModel(
    private val transitGraphRepository: TransitGraphRepository,
    private val realtimeRepository: RealtimeRepository,
    private val mapDownloader: MapDownloader,
    private val walkingRouteProvider: WalkingRouteProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouteDetailsUiState())
    val uiState: StateFlow<RouteDetailsUiState> = _uiState.asStateFlow()

    val liveVehicles: StateFlow<List<LiveVehicle>> = realtimeRepository.vehicles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val realtimeStatus: StateFlow<RealtimeStatus> = realtimeRepository.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RealtimeStatus.Offline())

    fun getTripDelaySeconds(tripId: String?): Int? {
        if (tripId.isNullOrBlank()) return null
        realtimeRepository.tripDelaysSec[tripId]?.let { return it }
        val core = tripId.substringAfter('_', "")
        if (core.isNotEmpty()) {
            realtimeRepository.tripDelaysSec.entries
                .firstOrNull { it.key.substringAfter('_', "") == core }
                ?.let { return it.value }
        }
        return null
    }

    init {
        loadSelectedJourney()
        // Сразу при входе в детали поездки обновляем живые опоздания GTFS-RT,
        // а затем автоматически подтягиваем свежие задержки на каждый цикл опроса
        viewModelScope.launch {
            runCatching { realtimeRepository.refreshOnce() }
        }
        viewModelScope.launch {
            realtimeRepository.status.collect {
                applyLiveDelaysToCurrentJourney()
            }
        }
    }

    private fun applyLiveDelaysToCurrentJourney() {
        _uiState.update { state ->
            val current = state.journey ?: return@update state
            // Живые задержки GTFS-RT относятся только к сегодняшним рейсам
            if (current.isFutureDay) return@update state
            var changed = false
            val updatedLegs = current.legs.map { leg ->
                if (leg is JourneyLeg.TransitLeg) {
                    val liveDelay = getTripDelaySeconds(leg.tripId)
                    if (liveDelay != null && liveDelay != leg.delaySeconds) {
                        changed = true
                        val updatedStops = leg.stops.map { stop ->
                            stop.copy(effectiveTimeSec = stop.scheduledTimeSec + liveDelay)
                        }
                        val firstStop = updatedStops.firstOrNull() ?: leg.boardingStop
                        val lastStop = updatedStops.lastOrNull() ?: leg.alightingStop
                        leg.copy(
                            delaySeconds = liveDelay,
                            startTimeSec = firstStop.effectiveTimeSec,
                            endTimeSec = lastStop.effectiveTimeSec,
                            stops = updatedStops
                        )
                    } else {
                        leg
                    }
                } else {
                    leg
                }
            }
            if (!changed) return@update state

            // Синхронизируем стартовый и конечный пешие участки с обновлённым временем транспорта,
            // чтобы общее время поездки и время прибытия оставались точными
            val syncedLegs = updatedLegs.mapIndexed { idx, leg ->
                if (leg is JourneyLeg.WalkLeg) {
                    val walkDur = leg.durationSec.coerceAtLeast(GeoUtils.walkingTimeSeconds(leg.distanceMeters.toDouble()))
                    val nextTransit = updatedLegs.getOrNull(idx + 1) as? JourneyLeg.TransitLeg
                    val prevTransit = updatedLegs.getOrNull(idx - 1) as? JourneyLeg.TransitLeg
                    when {
                        idx == 0 && nextTransit != null -> leg.copy(
                            startTimeSec = nextTransit.startTimeSec - walkDur,
                            endTimeSec = nextTransit.startTimeSec
                        )
                        idx == updatedLegs.lastIndex && prevTransit != null -> leg.copy(
                            startTimeSec = prevTransit.endTimeSec,
                            endTimeSec = prevTransit.endTimeSec + walkDur
                        )
                        prevTransit != null -> leg.copy(
                            startTimeSec = prevTransit.endTimeSec,
                            endTimeSec = prevTransit.endTimeSec + walkDur
                        )
                        else -> leg
                    }
                } else {
                    leg
                }
            }

            val firstLeg = syncedLegs.firstOrNull()
            val lastLeg = syncedLegs.lastOrNull()
            val updatedTicket = ZtmTicketCalculator.calculateForLegs(
                legs = syncedLegs,
                isUlgowy = current.ticketRecommendation.isUlgowy
            )
            val updatedJourney = current.copy(
                legs = syncedLegs,
                departureTimeSec = firstLeg?.startTimeSec ?: current.departureTimeSec,
                arrivalTimeSec = lastLeg?.endTimeSec ?: current.arrivalTimeSec,
                ticketRecommendation = updatedTicket
            )
            state.copy(journey = updatedJourney)
        }
    }

    private fun loadSelectedJourney() {
        viewModelScope.launch {
            val journey = transitGraphRepository.selectedJourney.value
            val mapFile = mapDownloader.getLocalMapFile().takeIf { it.exists() && it.length() > 0L }

            val activeLines = ArrayList<RouteShapePolyline>()
            val outsideLines = ArrayList<RouteShapePolyline>()
            val walkLines = ArrayList<List<Pair<Double, Double>>>()
            val insights = HashMap<String, TransitLegInsight>()

            if (journey != null) {
                var transitLegIndex = 0
                for (leg in journey.legs) {
                    when (leg) {
                        is JourneyLeg.WalkLeg -> {
                            if (leg.distanceMeters > 15) {
                                walkLines.add(
                                    listOf(
                                        leg.fromLat to leg.fromLon,
                                        leg.toLat to leg.toLon
                                    )
                                )
                            }
                        }

                        is JourneyLeg.TransitLeg -> {
                            val journeyDayOffset = if (journey.isFutureDay) {
                                journey.id.removePrefix("d").substringBefore('_').toIntOrNull() ?: 0
                            } else {
                                0
                            }
                            insights[leg.tripId] = transitGraphRepository.computeLegInsight(
                                boardingStopId = leg.boardingStop.stopId,
                                alightingStopId = leg.alightingStop.stopId,
                                routeShortName = leg.routeShortName,
                                departureTimeSec = leg.boardingStop.scheduledTimeSec,
                                tripId = leg.tripId,
                                dayOffset = journeyDayOffset
                            )
                            val colorHex = RouteLegColors.hexForLeg(
                                transitLegIndex = transitLegIndex,
                                shortName = leg.routeShortName,
                                isTram = leg.isTram,
                                isRail = leg.isRail
                            )
                            val rawShape = transitGraphRepository.getShapePoints(leg.shapeId)
                                .map { it.lat to it.lon }
                            val legStopCoords = leg.stops.map { it.latitude to it.longitude }
                            val (prevStop, nextStop) = transitGraphRepository.getAdjacentStopsForTrip(
                                tripId = leg.tripId,
                                boardingStopId = leg.boardingStop.stopId,
                                alightingStopId = leg.alightingStop.stopId
                            )
                            val sliced = ShapeSlicer.slice(
                                shapePoints = rawShape,
                                legStops = legStopCoords,
                                prevStop = prevStop,
                                nextStop = nextStop
                            )
                            if (sliced.activeCoordinates.size >= 2) {
                                activeLines.add(
                                    RouteShapePolyline(
                                        legIndex = transitLegIndex,
                                        isTram = leg.isTram,
                                        colorHex = colorHex,
                                        coordinates = sliced.activeCoordinates
                                    )
                                )
                            }
                            sliced.outsideSegments.forEach { seg ->
                                if (seg.size >= 2) {
                                    outsideLines.add(
                                        RouteShapePolyline(
                                            legIndex = transitLegIndex,
                                            isTram = leg.isTram,
                                            colorHex = colorHex,
                                            coordinates = seg
                                        )
                                    )
                                }
                            }
                            transitLegIndex++
                        }
                    }
                }
            }

            _uiState.update {
                it.copy(
                    journey = journey,
                    polylines = activeLines,
                    outsidePolylines = outsideLines,
                    walkPolylines = walkLines,
                    legInsights = insights,
                    localMapFile = mapFile
                )
            }

            // Параллельно достраиваем пешие участки по реальным дорогам/тротуарам (OSRM)
            // и обновляем реальную дистанцию в метрах и время ходьбы на таймлайне.
            // Для смены платформы на одной остановке (например, Górczyn PKM) и коротких переходов ≤ 120 м
            // НЕ отправляем в OSRM, чтобы он не притягивал точку к эстакаде/мосту с крюком на 600+ м.
            val currentJourney = _uiState.value.journey
            val indexedWalkLegs = currentJourney?.legs
                ?.mapIndexedNotNull { idx, leg ->
                    if (leg is JourneyLeg.WalkLeg && leg.distanceMeters > 15) idx to leg else null
                }
                .orEmpty()
            if (indexedWalkLegs.isNotEmpty() && currentJourney != null) {
                val routedResults = coroutineScope {
                    indexedWalkLegs.map { (legIdx, leg) ->
                        async {
                            val sameStopPlatformChange =
                                RaptorData.normalizeStopName(leg.fromName) == RaptorData.normalizeStopName(leg.toName)
                            val straightDist = GeoUtils.haversineMeters(
                                leg.fromLat, leg.fromLon,
                                leg.toLat, leg.toLon
                            )
                            val directFallback = pl.poznan.offdojade.data.location.WalkingRouteResult(
                                points = listOf(leg.fromLat to leg.fromLon, leg.toLat to leg.toLon),
                                distanceMeters = straightDist.toInt().coerceAtLeast(1)
                            )
                            if (sameStopPlatformChange || straightDist <= 120.0) {
                                legIdx to directFallback
                            } else {
                                val osrm = walkingRouteProvider.getWalkingRoute(
                                    fromLat = leg.fromLat,
                                    fromLon = leg.fromLon,
                                    toLat = leg.toLat,
                                    toLon = leg.toLon
                                )
                                // Защита от ошибочной привязки OSRM к мосту/виадуку:
                                // если OSRM дал крюк > 2.5× + 150 м от прямой — откатываемся на прямую с коэф. 1.25
                                val osrmDist = osrm.distanceMeters
                                if (osrmDist != null && osrmDist > (straightDist * 2.5 + 150.0)) {
                                    legIdx to directFallback.copy(
                                        distanceMeters = (straightDist * 1.25).toInt().coerceAtLeast(1)
                                    )
                                } else {
                                    legIdx to osrm
                                }
                            }
                        }
                    }.awaitAll()
                }
                val routedByLegIdx = routedResults.toMap()
                _uiState.update { state ->
                    val j = state.journey ?: return@update state
                    val newLegs = j.legs.mapIndexed { idx, leg ->
                        val res = routedByLegIdx[idx]
                        if (leg is JourneyLeg.WalkLeg && res != null) {
                            val realDist = res.distanceMeters
                                ?: (leg.distanceMeters * 1.25).toInt()
                            val realWalkSec = GeoUtils.walkingTimeSeconds(realDist.toDouble())
                            when {
                                idx == 0 -> leg.copy(
                                    distanceMeters = realDist,
                                    startTimeSec = leg.endTimeSec - realWalkSec
                                )
                                else -> leg.copy(
                                    distanceMeters = realDist,
                                    endTimeSec = leg.startTimeSec + realWalkSec
                                )
                            }
                        } else {
                            leg
                        }
                    }
                    state.copy(
                        journey = j.copy(
                            legs = newLegs,
                            departureTimeSec = newLegs.firstOrNull()?.startTimeSec ?: j.departureTimeSec,
                            arrivalTimeSec = newLegs.lastOrNull()?.endTimeSec ?: j.arrivalTimeSec
                        ),
                        walkPolylines = routedResults.map { it.second.points }
                    )
                }
            }
            applyLiveDelaysToCurrentJourney()
        }
    }
}
