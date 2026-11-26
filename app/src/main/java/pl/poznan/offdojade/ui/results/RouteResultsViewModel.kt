package pl.poznan.offdojade.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.poznan.offdojade.data.location.LocationProvider
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.domain.routing.RaptorEngine
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import java.time.LocalTime

data class RouteResultsUiState(
    val fromStopId: String? = null,
    val fromStopName: String = "Моё местоположение",
    val targetStopId: String = "",
    val targetStopName: String = "",
    val isLoading: Boolean = true,
    val journeys: List<TransitJourney> = emptyList(),
    val searchDurationMs: Long = 0L,
    val errorMessage: String? = null
)

class RouteResultsViewModel(
    private val transitGraphRepository: TransitGraphRepository,
    private val raptorEngine: RaptorEngine,
    private val realtimeRepository: RealtimeRepository,
    private val locationProvider: LocationProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(RouteResultsUiState())
    val uiState: StateFlow<RouteResultsUiState> = _uiState.asStateFlow()

    fun searchRoute(fromStopId: String?, targetStopId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    fromStopId = fromStopId,
                    targetStopId = targetStopId,
                    isLoading = true,
                    errorMessage = null
                )
            }

            val graph = transitGraphRepository.ensureLoadedForToday()
            if (graph == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Расписание ZTM не загружено. Выполните синхронизацию."
                    )
                }
                return@launch
            }

            val targetStop = transitGraphRepository.getStopById(targetStopId)
            val targetName = targetStop?.stopName ?: targetStopId
            val fromStop = fromStopId?.takeIf { it.isNotBlank() }?.let { transitGraphRepository.getStopById(it) }
            val fromName = fromStop?.stopName ?: "Моё местоположение"
            val nowSec = LocalTime.now().toSecondOfDay()

            val results = if (!fromStopId.isNullOrBlank()) {
                withContext(Dispatchers.Default) {
                    raptorEngine.searchFromStop(
                        data = graph,
                        originStopId = fromStopId,
                        targetStopId = targetStopId,
                        departureTimeSec = nowSec,
                        tripDelays = realtimeRepository.tripDelaysSec
                    )
                }
            } else {
                val userLocation = locationProvider.getCurrentOrFallbackLocation()
                withContext(Dispatchers.Default) {
                    raptorEngine.searchFromCoordinates(
                        data = graph,
                        originLat = userLocation.latitude,
                        originLon = userLocation.longitude,
                        targetStopId = targetStopId,
                        departureTimeSec = nowSec,
                        tripDelays = realtimeRepository.tripDelaysSec
                    )
                }
            }

            _uiState.update {
                it.copy(
                    fromStopName = fromName,
                    targetStopName = targetName,
                    isLoading = false,
                    journeys = results,
                    searchDurationMs = results.firstOrNull()?.searchDurationMs ?: 0L,
                    errorMessage = if (results.isEmpty()) "Маршруты на ближайшее время не найдены" else null
                )
            }
        }
    }

    fun selectJourney(journey: TransitJourney) {
        transitGraphRepository.selectJourney(journey)
    }
}
