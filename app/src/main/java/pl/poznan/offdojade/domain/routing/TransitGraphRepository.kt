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
import pl.poznan.offdojade.data.local.entity.RouteEntity
import pl.poznan.offdojade.data.local.entity.ShapePointEntity
import pl.poznan.offdojade.data.local.entity.StopEntity
import pl.poznan.offdojade.domain.model.RouteSearchRequest
import pl.poznan.offdojade.domain.model.StopGroup
import pl.poznan.offdojade.domain.model.TransitJourney
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

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

    /** Активный запрос маршрута (поддерживает и остановки, и адреса) */
    private val _activeSearchRequest = MutableStateFlow<RouteSearchRequest?>(null)
    val activeSearchRequest: StateFlow<RouteSearchRequest?> = _activeSearchRequest.asStateFlow()

    @Volatile
    private var cachedStopGroups: List<StopGroup>? = null

    fun selectJourney(journey: TransitJourney) {
        _selectedJourney.value = journey
    }

    fun setActiveSearchRequest(request: RouteSearchRequest) {
        _activeSearchRequest.value = request
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
                    cachedStopGroups = null
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

                // Если активных сервисов на точную дату нет (архив устарел), берем ТОЛЬКО сервисы того же дня недели
                val serviceIdsToLoad = if (activeServices.isNotEmpty()) {
                    activeServices.toList()
                } else {
                    calendars
                        .filter { cal -> matchesDayOfWeek(cal, date.dayOfWeek) }
                        .map { it.serviceId }
                        .distinct()
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
                cachedStopGroups = groupStopsByName(stops)
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
     * Поиск остановок с автоматическим объединением одинаковых по названию платформ в одну [StopGroup],
     * поддержкой польских диакритических знаков (`ó→o`, `ź/ż→z`, `ł→l`, `ą→a`, `ę→e`, `ś→s`, `ć→c`, `ń→n`)
     * и нечётким/пословным совпадением.
     */
    suspend fun searchStopGroups(query: String, limit: Int = 25): List<StopGroup> =
        withContext(Dispatchers.Default) {
            val allGroups = cachedStopGroups?.takeIf { it.isNotEmpty() } ?: withContext(Dispatchers.IO) {
                groupStopsByName(dao.getAllStops()).also { loaded ->
                    if (loaded.isNotEmpty()) cachedStopGroups = loaded
                }
            }
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                return@withContext allGroups
                    .sortedBy { it.displayName }
                    .take(limit)
            }
            allGroups.asSequence()
                .mapNotNull { group ->
                    val score = TextSearchUtils.matchScore(trimmed, group.displayName)
                    if (score > 0) group to score else null
                }
                .sortedWith(
                    compareByDescending<Pair<StopGroup, Int>> { it.second }
                        .thenBy { it.first.displayName }
                )
                .take(limit)
                .map { it.first }
                .toList()
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

    /**
     * Возвращает координаты 1 остановки непосредственно ДО [boardingStopId] и 1 остановки
     * непосредственно ПОСЛЕ [alightingStopId] на рейсе [tripId] (для полупрозрачного контекста на карте).
     */
    suspend fun getAdjacentStopsForTrip(
        tripId: String,
        boardingStopId: String,
        alightingStopId: String
    ): Pair<Pair<Double, Double>?, Pair<Double, Double>?> {
        val g = _graph.value
        if (g != null) {
            val tripIdx = g.tripIds.indexOf(tripId)
            if (tripIdx >= 0) {
                val patternIdx = g.tripPatternIndex[tripIdx]
                val stopCount = g.patternStopCount(patternIdx)
                var boardPos = -1
                var alightPos = -1
                for (pos in 0 until stopCount) {
                    val sIdx = g.patternStopAt(patternIdx, pos)
                    val sId = g.stopIds[sIdx]
                    if (boardPos == -1 && sId == boardingStopId) boardPos = pos
                    if (boardPos != -1 && sId == alightingStopId) {
                        alightPos = pos
                        break
                    }
                }
                val prev = if (boardPos > 0) {
                    val pIdx = g.patternStopAt(patternIdx, boardPos - 1)
                    g.stopLats[pIdx] to g.stopLons[pIdx]
                } else null
                val next = if (alightPos in 0 until stopCount - 1) {
                    val nIdx = g.patternStopAt(patternIdx, alightPos + 1)
                    g.stopLats[nIdx] to g.stopLons[nIdx]
                } else null
                return prev to next
            }
        }
        return null to null
    }

    private fun matchesDayOfWeek(
        cal: pl.poznan.offdojade.data.local.entity.CalendarEntity,
        dayOfWeek: DayOfWeek
    ): Boolean = when (dayOfWeek) {
        DayOfWeek.MONDAY -> cal.monday
        DayOfWeek.TUESDAY -> cal.tuesday
        DayOfWeek.WEDNESDAY -> cal.wednesday
        DayOfWeek.THURSDAY -> cal.thursday
        DayOfWeek.FRIDAY -> cal.friday
        DayOfWeek.SATURDAY -> cal.saturday
        DayOfWeek.SUNDAY -> cal.sunday
    }

    /**
     * Возвращает только те маршруты, которые реально курсируют в указанный день [date].
     */
    suspend fun getAllRoutesSorted(date: LocalDate = LocalDate.now()): List<RouteEntity> = withContext(Dispatchers.IO) {
        val dateInt = ServiceCalendarResolver.toYyyymmdd(date)
        val calendars = dao.getAllCalendars()
        val calendarDates = dao.getCalendarDatesForDate(dateInt)
        val activeServices = ServiceCalendarResolver.resolveActiveServiceIds(
            date = date,
            calendars = calendars,
            calendarDatesForDate = calendarDates
        ).ifEmpty {
            calendars.filter { matchesDayOfWeek(it, date.dayOfWeek) }.map { it.serviceId }.toSet()
        }.toList()

        val activeRouteIds = if (activeServices.isNotEmpty()) {
            activeServices.chunked(500).flatMap { chunk ->
                dao.getActiveRouteIdsForServices(chunk)
            }.toHashSet()
        } else {
            emptySet()
        }

        val allRoutes = dao.getAllRoutes()
        val filtered = if (activeRouteIds.isNotEmpty()) {
            allRoutes.filter { it.routeId in activeRouteIds }
        } else {
            emptyList()
        }

        filtered.sortedWith(
            compareBy<RouteEntity>(
                { it.routeType },
                { it.shortName.toIntOrNull() ?: Int.MAX_VALUE },
                { it.shortName }
            )
        )
    }

    suspend fun loadStaticTimetableForRoute(
        route: RouteEntity,
        dayType: TimetableDayType
    ): List<StaticDirectionTimetable> = withContext(Dispatchers.IO) {
        val targetDate = when (dayType) {
            TimetableDayType.TODAY -> LocalDate.now()
            TimetableDayType.WEEKDAY -> LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY))
            TimetableDayType.SATURDAY -> LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            TimetableDayType.SUNDAY -> LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        }
        val dateInt = ServiceCalendarResolver.toYyyymmdd(targetDate)
        val calendars = dao.getAllCalendars()
        val calendarDates = dao.getCalendarDatesForDate(dateInt)
        val activeServices = ServiceCalendarResolver.resolveActiveServiceIds(
            date = targetDate,
            calendars = calendars,
            calendarDatesForDate = calendarDates
        ).ifEmpty {
            // Строго по нужному дню недели — маршруты других дней не подмешиваем
            calendars.filter { cal ->
                when (dayType) {
                    TimetableDayType.TODAY -> matchesDayOfWeek(cal, targetDate.dayOfWeek)
                    TimetableDayType.WEEKDAY -> cal.monday || cal.tuesday || cal.wednesday || cal.thursday || cal.friday
                    TimetableDayType.SATURDAY -> cal.saturday
                    TimetableDayType.SUNDAY -> cal.sunday
                }
            }.map { it.serviceId }.toSet()
        }.toList()

        if (activeServices.isEmpty()) return@withContext emptyList()

        val trips = activeServices.chunked(500).flatMap { chunk ->
            dao.getTripsForRouteAndServices(route.routeId, chunk)
        }
        if (trips.isEmpty()) return@withContext emptyList()

        val stopTimes = trips.map { it.tripId }.chunked(500).flatMap { chunk ->
            dao.getStopTimesForTrips(chunk)
        }
        val stopById = dao.getAllStops().associateBy { it.stopId }
        val stByTrip = stopTimes.groupBy { it.tripId }

        val byDirection = trips.groupBy { it.directionId }
        byDirection.entries.sortedBy { it.key }.mapNotNull { (dirId, dirTrips) ->
            // Находим самый длинный рейс направления для канонического списка остановок
            val canonicalTrip = dirTrips.maxByOrNull { stByTrip[it.tripId]?.size ?: 0 } ?: return@mapNotNull null
            val canonicalStops = stByTrip[canonicalTrip.tripId]
                .orEmpty()
                .sortedBy { it.stopSequence }
                .mapNotNull { st -> stopById[st.stopId] }
            if (canonicalStops.isEmpty()) return@mapNotNull null

            val headsign = dirTrips
                .map { it.tripHeadsign.trim() }
                .filter { it.isNotEmpty() }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?: canonicalStops.last().stopName

            // Собираем отправления для каждой остановки (используем stopId, а также объединяем все отправления этого направления)
            val departuresByStopId = HashMap<String, MutableList<StaticDepartureEntry>>()
            for (trip in dirTrips) {
                val tripStops = stByTrip[trip.tripId]?.sortedBy { it.stopSequence } ?: continue
                // Исключаем последнюю остановку (высадка)
                for (i in 0 until tripStops.lastIndex) {
                    val st = tripStops[i]
                    departuresByStopId.getOrPut(st.stopId) { ArrayList() }.add(
                        StaticDepartureEntry(
                            departureTimeSec = st.departureTimeSec,
                            isLowFloor = trip.wheelchairAccessible == 1,
                            headsign = trip.tripHeadsign.ifBlank { headsign }
                        )
                    )
                }
            }

            val stopTimetables = canonicalStops.mapIndexed { index, stop ->
                val deps = departuresByStopId[stop.stopId]
                    .orEmpty()
                    .distinctBy { it.departureTimeSec }
                    .sortedBy { it.departureTimeSec }
                StaticStopTimetable(
                    stopId = stop.stopId,
                    stopName = stop.stopName,
                    zoneId = stop.zoneId.ifBlank { "A" },
                    platformCode = stop.platformCode,
                    isTerminal = index == canonicalStops.lastIndex,
                    departures = deps
                )
            }

            StaticDirectionTimetable(
                directionId = dirId,
                headsign = headsign,
                stops = stopTimetables
            )
        }
    }
}

enum class TimetableDayType {
    TODAY,
    WEEKDAY,
    SATURDAY,
    SUNDAY
}

data class StaticDepartureEntry(
    val departureTimeSec: Int,
    val isLowFloor: Boolean,
    val headsign: String
)

data class StaticStopTimetable(
    val stopId: String,
    val stopName: String,
    val zoneId: String,
    val platformCode: String,
    val isTerminal: Boolean,
    val departures: List<StaticDepartureEntry>
)

data class StaticDirectionTimetable(
    val directionId: Int,
    val headsign: String,
    val stops: List<StaticStopTimetable>
)
