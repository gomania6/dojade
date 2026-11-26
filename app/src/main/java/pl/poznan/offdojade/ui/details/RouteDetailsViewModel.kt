package pl.poznan.offdojade.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.poznan.offdojade.data.map.MapDownloader
import pl.poznan.offdojade.data.realtime.LiveVehicle
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.domain.routing.ShapeSlicer
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import java.io.File

data class RouteShapePolyline(
    val isTram: Boolean,
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
    val localMapFile: File? = null
)

class RouteDetailsViewModel(
    private val transitGraphRepository: TransitGraphRepository,
    private val realtimeRepository: RealtimeRepository,
    private val mapDownloader: MapDownloader
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouteDetailsUiState())
    val uiState: StateFlow<RouteDetailsUiState> = _uiState.asStateFlow()

    val liveVehicles: StateFlow<List<LiveVehicle>> = realtimeRepository.vehicles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

            if (journey != null) {
                for (leg in journey.legs) {
                    when (leg) {
                        is JourneyLeg.WalkLeg -> {
                            walkLines.add(
                                listOf(
                                    leg.fromLat to leg.fromLon,
                                    leg.toLat to leg.toLon
                                )
                            )
                        }

                        is JourneyLeg.TransitLeg -> {
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
                                        isTram = leg.isTram,
                                        coordinates = sliced.activeCoordinates
                                    )
                                )
                            }
                            sliced.outsideSegments.forEach { seg ->
                                if (seg.size >= 2) {
                                    outsideLines.add(
                                        RouteShapePolyline(
                                            isTram = leg.isTram,
                                            coordinates = seg
                                        )
                                    )
                                }
                            }
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
                    localMapFile = mapFile
                )
            }
        }
    }
}
