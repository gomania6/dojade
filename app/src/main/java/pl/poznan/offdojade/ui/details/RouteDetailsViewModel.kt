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
import pl.poznan.offdojade.data.location.WalkingRouteProvider
import pl.poznan.offdojade.data.map.MapDownloader
import pl.poznan.offdojade.data.realtime.LiveVehicle
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.data.realtime.RealtimeStatus
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.domain.routing.ShapeSlicer
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import pl.poznan.offdojade.domain.routing.TransitLegInsight
import java.io.File

/**
 * Мягкая палитра цветов для сегментов маршрута (ночные автобусы отображаются белым).
 */
object RouteLegColors {
    const val NIGHT_BUS_HEX = "#EAEFF5"

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
        isTram: Boolean = false
    ): String {
        if (!isTram && shortName.isNotBlank() && TransportMode.isNightLine(shortName)) {
            return NIGHT_BUS_HEX
        }
        return HEX_PALETTE[transitLegIndex.coerceAtLeast(0) % HEX_PALETTE.size]
    }
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
        return realtimeRepository.tripDelaysSec[tripId]
    }

    init {
        loadSelectedJourney()
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
                            insights[leg.tripId] = transitGraphRepository.computeLegInsight(
                                boardingStopId = leg.boardingStop.stopId,
                                alightingStopId = leg.alightingStop.stopId,
                                routeShortName = leg.routeShortName,
                                departureTimeSec = leg.boardingStop.scheduledTimeSec
                            )
                            val colorHex = RouteLegColors.hexForLeg(
                                transitLegIndex = transitLegIndex,
                                shortName = leg.routeShortName,
                                isTram = leg.isTram
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

            // Параллельно достраиваем пешие участки по реальным дорогам/тротуарам (OSRM),
            // с мгновенным сохранением прямой линии, если телефон офлайн
            val walkLegs = journey?.legs
                ?.filterIsInstance<JourneyLeg.WalkLeg>()
                ?.filter { it.distanceMeters > 15 }
                .orEmpty()
            if (walkLegs.isNotEmpty()) {
                val routedWalks = coroutineScope {
                    walkLegs.map { leg ->
                        async {
                            walkingRouteProvider.getWalkingPolyline(
                                fromLat = leg.fromLat,
                                fromLon = leg.fromLon,
                                toLat = leg.toLat,
                                toLon = leg.toLon
                            )
                        }
                    }.awaitAll()
                }
                _uiState.update { it.copy(walkPolylines = routedWalks) }
            }
        }
    }
}
