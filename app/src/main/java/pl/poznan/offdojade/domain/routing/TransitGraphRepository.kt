package pl.poznan.offdojade.domain.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.poznan.offdojade.data.gtfs.ServiceCalendarResolver
import pl.poznan.offdojade.data.local.DatabaseHolder
import pl.poznan.offdojade.data.local.entity.ShapePointEntity
import pl.poznan.offdojade.data.local.entity.StopEntity
import pl.poznan.offdojade.domain.model.StopGroup
import pl.poznan.offdojade.domain.model.TransitJourney
import java.time.LocalDate

/**
 * Управляет In-Memory графом RAPTOR на текущий день.
 * Загружает расписание из Room один раз при старте приложения и после обновления базы.
 */
class TransitGraphRepository(
    private val databaseHolder: DatabaseHolder
) {
    private val dao get() = databaseHolder.dao
    private val mutex = Mutex()

    private val _graph = MutableStateFlow<RaptorData?>(null)
    val graph: StateFlow<RaptorData?> = _graph.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Кэш последнего выбранного маршрута для мгновенного перехода на экран деталей и карты */
    private val _selectedJourney = MutableStateFlow<TransitJourney?>(null)
    val selectedJourney: StateFlow<TransitJourney?> = _selectedJourney.asStateFlow()

    fun selectJourney(journey: TransitJourney) {
        _selectedJourney.value = journey
    }

    suspend fun hasDatabaseData(): Boolean = withContext(Dispatchers.IO) {
        dao.getStopCount() > 0 && dao.getTripCount() > 0
    }

    suspend fun ensureLoadedForToday(): RaptorData? {
        val todayInt = ServiceCalendarResolver.toYyyymmdd(LocalDate.now())
        val existing = _graph.value
        if (existing != null && existing.serviceDateYyyymmdd == todayInt && existing.stopCount > 0) {
            return existing
        }
        return reloadForDate(LocalDate.now())
    }

    suspend fun reloadForToday(): RaptorData? = reloadForDate(LocalDate.now())

    suspend fun reloadForDate(date: LocalDate): RaptorData? = mutex.withLock {
        withContext(Dispatchers.Default) {
            _isLoading.value = true
            try {
                val stops = dao.getAllStops()
                if (stops.isEmpty()) {
                    _graph.value = null
                    return@withContext null
                }
                val dateInt = ServiceCalendarResolver.toYyyymmdd(date)
                val calendars = dao.getAllCalendars()
                val calendarDates = dao.getCalendarDatesForDate(dateInt)
                val activeServices = ServiceCalendarResolver.resolveActiveServiceIds(
                    date = date,
                    calendars = calendars,
                    calendarDatesForDate = calendarDates
                )

                val routes = dao.getAllRoutes()
                val transfers = dao.getAllTransfers()

                // Если активных сервисов на дату нет (например, архив устарел), берем все сервисы из calendars как fallback
                val serviceIdsToLoad = if (activeServices.isNotEmpty()) {
                    activeServices.toList()
                } else {
                    calendars.map { it.serviceId }.distinct()
                }

                // SQLite имеет лимит на кол-во bind-параметров (999) -> грузим чанками по 500
                val trips = serviceIdsToLoad.chunked(500).flatMap { chunk ->
                    dao.getTripsForServices(chunk)
                }
                val stopTimes = serviceIdsToLoad.chunked(500).flatMap { chunk ->
                    dao.getStopTimesForServices(chunk)
                }

                val built = TransitGraphBuilder.build(
                    serviceDateYyyymmdd = dateInt,
                    stops = stops,
                    routes = routes,
                    trips = trips,
                    stopTimes = stopTimes,
                    transfers = transfers
                )
                _graph.value = built
                built
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun searchStops(query: String, limit: Int = 25): List<StopEntity> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                dao.getAllStops().take(limit)
            } else {
                dao.searchStops(query.trim(), limit)
            }
        }

    /**
     * Поиск остановок с автоматическим объединением одинаковых по названию платформ в одну [StopGroup].
     */
    suspend fun searchStopGroups(query: String, limit: Int = 25): List<StopGroup> =
        withContext(Dispatchers.IO) {
            val raw = if (query.isBlank()) {
                dao.getAllStops().take(limit * 6)
            } else {
                dao.searchStops(query.trim(), limit * 6)
            }
            groupStopsByName(raw).take(limit)
        }

    companion object {
        fun groupStopsByName(stops: List<StopEntity>): List<StopGroup> {
            val grouped = LinkedHashMap<String, MutableList<StopEntity>>()
            for (stop in stops) {
                val key = RaptorData.normalizeStopName(stop.stopName)
                grouped.getOrPut(key) { ArrayList(2) }.add(stop)
            }
            return grouped.values.map { list ->
                val first = list.first()
                StopGroup(
                    displayName = first.stopName.trim(),
                    primaryStopId = first.stopId,
                    stopIds = list.map { it.stopId },
                    zones = list.map { it.zoneId.trim().uppercase() }.filter { it.isNotEmpty() }.distinct().sorted(),
                    platformCount = list.size,
                    latitude = list.map { it.stopLat }.average(),
                    longitude = list.map { it.stopLon }.average()
                )
            }
        }
    }

    suspend fun getStopById(stopId: String): StopEntity? =
        withContext(Dispatchers.IO) { dao.getStopById(stopId) }

    suspend fun getShapePoints(shapeId: String): List<ShapePointEntity> =
        withContext(Dispatchers.IO) {
            if (shapeId.isBlank()) emptyList() else dao.getShapePoints(shapeId)
        }
}
