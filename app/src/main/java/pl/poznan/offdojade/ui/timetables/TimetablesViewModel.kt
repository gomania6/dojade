package pl.poznan.offdojade.ui.timetables

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.poznan.offdojade.data.local.entity.RouteEntity
import pl.poznan.offdojade.data.map.MapDownloader
import java.io.File
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.domain.routing.StaticDirectionTimetable
import pl.poznan.offdojade.domain.routing.StaticStopTimetable
import pl.poznan.offdojade.domain.routing.TextSearchUtils
import pl.poznan.offdojade.domain.routing.TimetableDayType
import pl.poznan.offdojade.domain.routing.TransitGraphRepository

enum class LineCategoryFilter {
    TRAMS,
    BUSES,
    RAIL
}

data class TimetablesUiState(
    val isLoadingRoutes: Boolean = true,
    val allRoutes: List<RouteEntity> = emptyList(),
    val searchQuery: String = "",
    val categoryFilter: LineCategoryFilter = LineCategoryFilter.TRAMS,

    val selectedRoute: RouteEntity? = null,
    val selectedDayType: TimetableDayType = TimetableDayType.TODAY,
    val isLoadingTimetable: Boolean = false,
    val directions: List<StaticDirectionTimetable> = emptyList(),
    val selectedDirectionIdx: Int = 0,
    val selectedStop: StaticStopTimetable? = null
) {
    val filteredRoutes: List<RouteEntity>
        get() {
            val q = TextSearchUtils.foldDiacritics(searchQuery)
            return allRoutes.filter { route ->
                if (q.isNotEmpty()) {
                    // При поиске ищем по всем линиям сразу, чтобы не переключать вкладку вручную
                    TextSearchUtils.foldDiacritics(route.shortName).contains(q) ||
                        TextSearchUtils.foldDiacritics(route.longName).contains(q)
                } else {
                    val isRailType = route.routeType == 2 || route.routeType == 100 || route.routeType == 109
                    when (categoryFilter) {
                        LineCategoryFilter.TRAMS -> route.routeType == 0
                        LineCategoryFilter.BUSES -> route.routeType != 0 && !isRailType
                        LineCategoryFilter.RAIL -> isRailType
                    }
                }
            }
        }

    val currentDirection: StaticDirectionTimetable?
        get() = directions.getOrNull(selectedDirectionIdx)
}

class TimetablesViewModel(
    private val transitGraphRepository: TransitGraphRepository,
    private val mapDownloader: MapDownloader
) : ViewModel() {

    fun getLocalMapFile(): File? =
        mapDownloader.getLocalMapFile().takeIf { it.exists() && it.length() > 0L }

    private val _uiState = MutableStateFlow(TimetablesUiState())
    val uiState: StateFlow<TimetablesUiState> = _uiState.asStateFlow()

    private var timetableJob: Job? = null

    init {
        loadRoutes()
        // Автоматически обновляем список линий, как только граф/расписание догрузилось или обновилось в фоне
        viewModelScope.launch {
            transitGraphRepository.graph.collect { graph ->
                if (graph != null) {
                    loadRoutes()
                }
            }
        }
    }

    fun loadRoutes() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingRoutes = true) }
            val routes = transitGraphRepository.getAllRoutesSorted()
            _uiState.update { it.copy(isLoadingRoutes = false, allRoutes = routes) }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun onCategoryChange(filter: LineCategoryFilter) {
        _uiState.update { it.copy(categoryFilter = filter) }
    }

    fun selectRoute(route: RouteEntity) {
        _uiState.update {
            it.copy(
                selectedRoute = route,
                selectedDirectionIdx = 0,
                selectedStop = null
            )
        }
        loadTimetable(route, _uiState.value.selectedDayType)
    }

    fun clearSelectedRoute() {
        _uiState.update {
            it.copy(
                selectedRoute = null,
                directions = emptyList(),
                selectedStop = null
            )
        }
    }

    fun selectDayType(dayType: TimetableDayType) {
        val state = _uiState.value
        val route = state.selectedRoute ?: return
        val preserveStopId = state.selectedStop?.stopId
        val preserveDirIdx = state.selectedDirectionIdx
        _uiState.update { it.copy(selectedDayType = dayType) }
        loadTimetable(
            route = route,
            dayType = dayType,
            preferredDirectionIdx = preserveDirIdx,
            preferredStopId = preserveStopId
        )
    }

    fun selectDirection(index: Int) {
        _uiState.update { it.copy(selectedDirectionIdx = index, selectedStop = null) }
    }

    fun selectStop(stop: StaticStopTimetable) {
        _uiState.update { it.copy(selectedStop = stop) }
    }

    fun clearSelectedStop() {
        _uiState.update { it.copy(selectedStop = null) }
    }

    private fun loadTimetable(
        route: RouteEntity,
        dayType: TimetableDayType,
        preferredDirectionIdx: Int = 0,
        preferredStopId: String? = null
    ) {
        timetableJob?.cancel()
        timetableJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingTimetable = true, directions = emptyList()) }
            val dirs = transitGraphRepository.loadStaticTimetableForRoute(route, dayType)
            val safeDirIdx = preferredDirectionIdx.coerceIn(0, (dirs.lastIndex).coerceAtLeast(0))
            val matchedStop = if (preferredStopId != null) {
                dirs.getOrNull(safeDirIdx)?.stops?.firstOrNull { it.stopId == preferredStopId }
                    ?: dirs.firstNotNullOfOrNull { d -> d.stops.firstOrNull { it.stopId == preferredStopId } }
            } else {
                null
            }
            val resolvedDirIdx = if (matchedStop != null) {
                dirs.indexOfFirst { d -> d.stops.any { it.stopId == matchedStop.stopId } }
                    .takeIf { it >= 0 } ?: safeDirIdx
            } else {
                safeDirIdx
            }
            _uiState.update {
                it.copy(
                    isLoadingTimetable = false,
                    directions = dirs,
                    selectedDirectionIdx = resolvedDirIdx,
                    selectedStop = if (preferredStopId != null) matchedStop else null
                )
            }
        }
    }
}
