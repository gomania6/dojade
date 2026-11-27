package pl.poznan.offdojade.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.poznan.offdojade.data.location.LocationProvider
import pl.poznan.offdojade.data.location.UserLocation
import pl.poznan.offdojade.data.map.MapDownloader
import pl.poznan.offdojade.data.realtime.LiveVehicle
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.data.realtime.RealtimeStatus
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.domain.routing.LiveRouteStopPoint
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import java.io.File

enum class LiveMapVehicleFilter {
    ALL,
    TRAMS,
    BUSES
}

data class EnrichedMapVehicle(
    val vehicleId: String,
    val lineLabel: String,
    val isTram: Boolean,
    val isNight: Boolean,
    val headsign: String,
    val tripId: String?,
    val routeId: String?,
    val latitude: Double,
    val longitude: Double,
    val bearing: Float?,
    val delaySeconds: Int,
    val timestampEpochSec: Long?,
    val currentStopId: String?,
    val colorHex: String
)

data class SelectedMapVehicleInfo(
    val vehicle: EnrichedMapVehicle,
    val headsign: String,
    val currentStopName: String?,
    val nextStopName: String?,
    val routeCoordinates: List<Pair<Double, Double>>,
    val routeStops: List<LiveRouteStopPoint>,
    val lastUpdatedEpochMs: Long
)

data class LiveMapUiState(
    val localMapFile: File? = null,
    val userLocation: UserLocation? = null,
    val filter: LiveMapVehicleFilter = LiveMapVehicleFilter.ALL,
    val lineQuery: String = "",
    val allVehicles: List<EnrichedMapVehicle> = emptyList(),
    val filteredVehicles: List<EnrichedMapVehicle> = emptyList(),
    val selectedVehicle: SelectedMapVehicleInfo? = null,
    val isRefreshing: Boolean = false
)

class LiveMapViewModel(
    private val realtimeRepository: RealtimeRepository,
    private val transitGraphRepository: TransitGraphRepository,
    private val mapDownloader: MapDownloader,
    private val locationProvider: LocationProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LiveMapUiState(
            localMapFile = mapDownloader.getLocalMapFile().takeIf { it.exists() && it.length() > 0L }
        )
    )
    val uiState: StateFlow<LiveMapUiState> = _uiState.asStateFlow()

    val realtimeStatus: StateFlow<RealtimeStatus> = realtimeRepository.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RealtimeStatus.Offline())

    private var selectJob: Job? = null

    init {
        viewModelScope.launch {
            transitGraphRepository.ensureLoadedForToday()
            val loc = locationProvider.getCurrentOrFallbackLocation()
            _uiState.update { it.copy(userLocation = loc) }
        }
        viewModelScope.launch {
            runCatching { realtimeRepository.refreshOnce() }
        }
        viewModelScope.launch {
            combine(
                realtimeRepository.vehicles,
                transitGraphRepository.graph
            ) { rawVehicles, _ ->
                rawVehicles
            }.collect { rawVehicles ->
                val enriched = withContext(Dispatchers.Default) {
                    rawVehicles.map { v -> enrichVehicle(v) }
                }
                val currentSelectedId = _uiState.value.selectedVehicle?.vehicle?.vehicleId
                _uiState.update { state ->
                    val filtered = applyFilter(enriched, state.filter, state.lineQuery)
                    val refreshedSelected = if (currentSelectedId != null) {
                        val updatedVeh = enriched.firstOrNull { it.vehicleId == currentSelectedId }
                        if (updatedVeh != null && state.selectedVehicle != null) {
                            val feedMs = (realtimeRepository.status.value as? RealtimeStatus.Live)?.lastUpdatedEpochMs
                                ?: System.currentTimeMillis()
                            val updatedMs = updatedVeh.timestampEpochSec?.takeIf { it > 0L }?.let { it * 1000L } ?: feedMs
                            state.selectedVehicle.copy(
                                vehicle = updatedVeh,
                                lastUpdatedEpochMs = updatedMs
                            )
                        } else {
                            state.selectedVehicle
                        }
                    } else {
                        null
                    }
                    state.copy(
                        allVehicles = enriched,
                        filteredVehicles = filtered,
                        selectedVehicle = refreshedSelected
                    )
                }
            }
        }
    }

    fun setFilter(filter: LiveMapVehicleFilter) {
        _uiState.update { state ->
            state.copy(
                filter = filter,
                filteredVehicles = applyFilter(state.allVehicles, filter, state.lineQuery)
            )
        }
    }

    fun onLineQueryChange(query: String) {
        _uiState.update { state ->
            state.copy(
                lineQuery = query,
                filteredVehicles = applyFilter(state.allVehicles, state.filter, query)
            )
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            runCatching { realtimeRepository.refreshOnce() }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun refreshUserLocation(onLocated: (UserLocation) -> Unit = {}) {
        viewModelScope.launch {
            val loc = locationProvider.getFreshOrFallbackLocation()
            _uiState.update { it.copy(userLocation = loc) }
            onLocated(loc)
        }
    }

    fun selectVehicleById(vehicleId: String) {
        val target = _uiState.value.allVehicles.firstOrNull { it.vehicleId == vehicleId } ?: return
        selectJob?.cancel()
        selectJob = viewModelScope.launch {
            val snapshot = withContext(Dispatchers.Default) {
                transitGraphRepository.resolveVehicleRouteSnapshot(
                    tripId = target.tripId,
                    routeId = target.routeId,
                    label = target.lineLabel,
                    currentStopId = target.currentStopId,
                    vehicleLat = target.latitude,
                    vehicleLon = target.longitude,
                    bearing = target.bearing
                )
            }

            val shapeCoords = if (snapshot != null && snapshot.shapeId.isNotBlank()) {
                val pts = transitGraphRepository.getShapePoints(snapshot.shapeId)
                if (pts.size >= 2) {
                    pts.map { it.lat to it.lon }
                } else {
                    snapshot.stops.map { it.latitude to it.longitude }
                }
            } else {
                snapshot?.stops?.map { it.latitude to it.longitude }.orEmpty()
            }

            val feedMs = (realtimeRepository.status.value as? RealtimeStatus.Live)?.lastUpdatedEpochMs
                ?: System.currentTimeMillis()
            val updatedMs = target.timestampEpochSec?.takeIf { it > 0L }?.let { it * 1000L } ?: feedMs
            val resolvedHeadsign = snapshot?.headsign?.takeIf { it.isNotBlank() } ?: target.headsign

            _uiState.update { state ->
                state.copy(
                    selectedVehicle = SelectedMapVehicleInfo(
                        vehicle = target.copy(
                            isTram = snapshot?.isTram ?: target.isTram,
                            headsign = resolvedHeadsign
                        ),
                        headsign = resolvedHeadsign,
                        currentStopName = snapshot?.currentStopName,
                        nextStopName = snapshot?.nextStopName,
                        routeCoordinates = shapeCoords,
                        routeStops = snapshot?.stops.orEmpty(),
                        lastUpdatedEpochMs = updatedMs
                    )
                )
            }
        }
    }

    fun clearSelection() {
        selectJob?.cancel()
        _uiState.update { it.copy(selectedVehicle = null) }
    }

    private fun enrichVehicle(v: LiveVehicle): EnrichedMapVehicle {
        val (line, isTram, headsign) = transitGraphRepository.resolveQuickHeadsignAndType(
            tripId = v.tripId,
            routeId = v.routeId,
            label = v.label
        )
        val isNight = TransportMode.isNightLine(line)
        val delaySec = resolveDelay(v.tripId)
        val colorHex = when {
            isNight -> "#EAEFF5"
            isTram -> "#3FA372"
            else -> "#4B83C3"
        }
        return EnrichedMapVehicle(
            vehicleId = v.vehicleId,
            lineLabel = line,
            isTram = isTram,
            isNight = isNight,
            headsign = headsign,
            tripId = v.tripId,
            routeId = v.routeId,
            latitude = v.latitude,
            longitude = v.longitude,
            bearing = v.bearing,
            delaySeconds = delaySec,
            timestampEpochSec = v.timestampEpochSec,
            currentStopId = v.currentStopId,
            colorHex = colorHex
        )
    }

    private fun resolveDelay(tripId: String?): Int {
        if (tripId.isNullOrBlank()) return 0
        realtimeRepository.tripDelaysSec[tripId]?.let { return it }
        val core = tripId.substringAfter('_', "")
        if (core.isNotEmpty()) {
            realtimeRepository.tripDelaysSec.entries
                .firstOrNull { it.key.substringAfter('_', "") == core }
                ?.let { return it.value }
        }
        return 0
    }

    private fun applyFilter(
        vehicles: List<EnrichedMapVehicle>,
        filter: LiveMapVehicleFilter,
        query: String
    ): List<EnrichedMapVehicle> {
        val trimmed = query.trim()
        return vehicles.filter { v ->
            val modeOk = when (filter) {
                LiveMapVehicleFilter.ALL -> true
                LiveMapVehicleFilter.TRAMS -> v.isTram
                LiveMapVehicleFilter.BUSES -> !v.isTram
            }
            val queryOk = trimmed.isEmpty() ||
                v.lineLabel.equals(trimmed, ignoreCase = true) ||
                v.lineLabel.startsWith(trimmed, ignoreCase = true) ||
                v.headsign.contains(trimmed, ignoreCase = true)
            modeOk && queryOk
        }
    }
}
