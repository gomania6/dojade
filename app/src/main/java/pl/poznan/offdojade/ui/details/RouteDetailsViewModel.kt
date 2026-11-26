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
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import java.io.File

data class RouteShapePolyline(
    val isTram: Boolean,
    val coordinates: List<Pair<Double, Double>>
)

data class RouteDetailsUiState(
    val journey: TransitJourney? = null,
    val polylines: List<RouteShapePolyline> = emptyList(),
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

            val polylines = if (journey != null) {
                journey.transitLegs.map { leg ->
                    val shapePoints = transitGraphRepository.getShapePoints(leg.shapeId)
                    val coords = if (shapePoints.size >= 2) {
                        shapePoints.map { it.lat to it.lon }
                    } else {
                        leg.stops.map { it.latitude to it.longitude }
                    }
                    RouteShapePolyline(isTram = leg.isTram, coordinates = coords)
                }
            } else {
                emptyList()
            }

            _uiState.update {
                it.copy(
                    journey = journey,
                    polylines = polylines,
                    localMapFile = mapFile
                )
            }
        }
    }
}
