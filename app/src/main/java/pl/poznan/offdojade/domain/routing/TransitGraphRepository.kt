package pl.poznan.offdojade.domain.routing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.poznan.offdojade.data.gtfs.ServiceCalendarResolver
import pl.poznan.offdojade.data.local.DatabaseHolder
import pl.poznan.offdojade.data.local.entity.RouteEntity
import pl.poznan.offdojade.data.local.entity.ShapePointEntity
import pl.poznan.offdojade.data.local.entity.StopEntity
import pl.poznan.offdojade.data.local.entity.StopTimeEntity
import pl.poznan.offdojade.domain.model.RouteSearchRequest
import pl.poznan.offdojade.domain.model.StopGroup
import pl.poznan.offdojade.domain.model.TransitJourney
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
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
    private val cacheIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

    @Volatile
    private var cachedAlternateDayGraph: Pair<Int, RaptorData>? = null

    @Volatile
    var cachedRouteResults: CachedRouteResults? = null

    fun selectJourney(journey: TransitJourney) {
        _selectedJourney.value = journey
    }

    fun setActiveSearchRequest(request: RouteSearchRequest) {
        // При каждом новом/повторном поиске с главного экрана сбрасываем кэш списка,
        // чтобы маршруты пересчитывались заново с актуального времени
        cachedRouteResults = null
        _activeSearchRequest.value = request
    }

    suspend fun hasDatabaseData(): Boolean = withContext(Dispatchers.IO) {
        dao.getStopCount() > 0 && dao.getTripCount() > 0
    }

    suspend fun ensureLoadedForToday(): RaptorData? {
        val today = LocalDate.now(WARSAW_ZONE)
        val todayInt = ServiceCalendarResolver.toYyyymmdd(today)
        val existing = _graph.value
        if (existing != null && existing.serviceDateYyyymmdd == todayInt && existing.stopCount > 0) {
            return existing
        }
        return loadOrRebuildForDate(date = today, forceRebuild = false)
    }

    suspend fun reloadForToday(preferDiskCache: Boolean = false): RaptorData? {
        if (preferDiskCache) {
            cachedStopGroups = null
            cachedAlternateDayGraph = null
            cachedRouteResults = null
            _graph.value = null
            return loadOrRebuildForDate(date = LocalDate.now(WARSAW_ZONE), forceRebuild = false)
        }
        return loadOrRebuildForDate(date = LocalDate.now(WARSAW_ZONE), forceRebuild = true)
    }

    /**
     * Строит (и кэширует) граф RAPTOR на произвольную дату (например, на завтра),
     * не перезаписывая текущий граф сегодняшнего дня.
     */
    suspend fun buildGraphForDate(date: LocalDate): RaptorData? = mutex.withLock {
        val dateInt = ServiceCalendarResolver.toYyyymmdd(date)
        val todayGraph = _graph.value
        if (todayGraph != null && todayGraph.serviceDateYyyymmdd == dateInt) {
            return@withLock todayGraph
        }
        cachedAlternateDayGraph?.let { (cachedDate, cachedGraph) ->
            if (cachedDate == dateInt) return@withLock cachedGraph
        }
        withContext(Dispatchers.Default) {
            // 1. Быстрая загрузка из бинарного кэша на диске (~50–120 мс)
            val dbFingerprint = databaseHolder.getDatabaseFingerprint()
            val cacheFile = databaseHolder.graphCacheFileForDate(dateInt)
            if (dbFingerprint != 0L) {
                val cached = RaptorDataCache.load(
                    file = cacheFile,
                    expectedDateYyyymmdd = dateInt,
                    expectedDbFingerprint = dbFingerprint
                )
                if (cached != null && cached.stopCount > 0) {
                    cachedAlternateDayGraph = dateInt to cached
                    return@withContext cached
                }
            }

            // 2. Быстрая сборка из Room SQLite (с раздельным fallback ZTM + ж/д и прямым чтением курсора)
            val stops = dao.getAllStops()
            if (stops.isEmpty()) return@withContext null
            val serviceIdsToLoad = resolveActiveServicesForDate(date)
            val routes = dao.getAllRoutes()
            val transfers = dao.getAllTransfers()
            val trips = serviceIdsToLoad.chunked(500).flatMap { chunk ->
                dao.getTripsForServices(chunk)
            }
            val stopTimes = loadStopTimesForServicesFast(serviceIdsToLoad)
            val built = TransitGraphBuilder.build(
                serviceDateYyyymmdd = dateInt,
                stops = stops,
                routes = routes,
                trips = trips,
                stopTimes = stopTimes,
                transfers = transfers
            )
            cachedAlternateDayGraph = dateInt to built
            if (dbFingerprint != 0L && built.stopCount > 0) {
                cacheIoScope.launch {
                    RaptorDataCache.save(cacheFile, dbFingerprint, built)
                }
            }
            built
        }
    }

    suspend fun reloadForDate(date: LocalDate): RaptorData? =
        loadOrRebuildForDate(date = date, forceRebuild = true)

    private suspend fun loadOrRebuildForDate(date: LocalDate, forceRebuild: Boolean): RaptorData? = mutex.withLock {
        val dateInt = ServiceCalendarResolver.toYyyymmdd(date)
        // Повторная проверка ВНУТРИ mutex: если другой поток (App / Activity / HomeVM) уже загрузил граф,
        // не пересобираем его повторно!
        if (!forceRebuild) {
            val existing = _graph.value
            if (existing != null && existing.serviceDateYyyymmdd == dateInt && existing.stopCount > 0) {
                return@withLock existing
            }
        }

        withContext(Dispatchers.Default) {
            _isLoading.value = true
            try {
                // 1. Сверхбыстрый путь: бинарный кэш графа на диске (~50–120 мс вместо 4–6 сек из SQLite)
                val dbFingerprint = databaseHolder.getDatabaseFingerprint()
                val cacheFile = databaseHolder.graphCacheFileForDate(dateInt)
                if (!forceRebuild && dbFingerprint != 0L) {
                    val cached = RaptorDataCache.load(
                        file = cacheFile,
                        expectedDateYyyymmdd = dateInt,
                        expectedDbFingerprint = dbFingerprint
                    )
                    if (cached != null && cached.stopCount > 0) {
                        cachedStopGroups = groupStopsFromRaptorData(cached)
                        _graph.value = cached
                        databaseHolder.pruneOldGraphCaches(dateInt)
                        return@withContext cached
                    }
                }

                // 2. Быстрая сборка из Room SQLite (с раздельным fallback ZTM + ж/д и асинхронным сохранением кэша)
                val stops = dao.getAllStops()
                if (stops.isEmpty()) {
                    cachedStopGroups = null
                    _graph.value = null
                    return@withContext null
                }
                val serviceIdsToLoad = resolveActiveServicesForDate(date)
                val routes = dao.getAllRoutes()
                val transfers = dao.getAllTransfers()

                // SQLite имеет лимит на кол-во bind-параметров (999) -> грузим чанками по 500
                val trips = serviceIdsToLoad.chunked(500).flatMap { chunk ->
                    dao.getTripsForServices(chunk)
                }
                val stopTimes = loadStopTimesForServicesFast(serviceIdsToLoad)

                val built = TransitGraphBuilder.build(
                    serviceDateYyyymmdd = dateInt,
                    stops = stops,
                    routes = routes,
                    trips = trips,
                    stopTimes = stopTimes,
                    transfers = transfers
                )
                cachedStopGroups = groupStopsByName(stops)
                cachedAlternateDayGraph = null
                _graph.value = built

                // Сохраняем бинарный кэш на диск асинхронно в фоне, не задерживая пользователя!
                if (dbFingerprint != 0L && built.stopCount > 0) {
                    cacheIoScope.launch {
                        RaptorDataCache.save(cacheFile, dbFingerprint, built)
                        databaseHolder.pruneOldGraphCaches(dateInt)
                    }
                }
                built
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun groupStopsFromRaptorData(data: RaptorData): List<StopGroup> {
        val byName = LinkedHashMap<String, MutableList<Int>>(data.stopCount / 2 + 1)
        for (i in 0 until data.stopCount) {
            val key = RaptorData.normalizeStopName(data.stopNames[i])
            byName.getOrPut(key) { ArrayList(2) }.add(i)
        }
        return byName.values.map { indices ->
            val first = indices.first()
            var sumLat = 0.0
            var sumLon = 0.0
            val zones = LinkedHashSet<String>(2)
            val ids = ArrayList<String>(indices.size)
            for (idx in indices) {
                sumLat += data.stopLats[idx]
                sumLon += data.stopLons[idx]
                val z = data.stopZones[idx].trim().uppercase()
                if (z.isNotEmpty()) zones.add(z)
                ids.add(data.stopIds[idx])
            }
            StopGroup(
                displayName = data.stopNames[first],
                primaryStopId = data.stopIds[first],
                stopIds = ids,
                zones = zones.sorted().ifEmpty { listOf("A") },
                platformCount = indices.size,
                latitude = sumLat / indices.size,
                longitude = sumLon / indices.size
            )
        }.sortedBy { it.displayName }
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
        private val WARSAW_ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
        private const val RAIL_SERVICE_PREFIX = "rail_"

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
        val candidateGraphs = listOfNotNull(_graph.value, cachedAlternateDayGraph?.second)
        for (g in candidateGraphs) {
            val tripIdx = g.tripIdToIndex[tripId] ?: continue
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

    suspend fun clearGraphCachesAndReload(): RaptorData? {
        databaseHolder.clearGraphCaches()
        cachedAlternateDayGraph = null
        cachedRouteResults = null
        return reloadForToday()
    }

    suspend fun getDatabaseCounts(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        dao.getStopCount() to dao.getTripCount()
    }

    /**
     * Прямая потоковая выборка `stop_times` по активным `service_id` без `ORDER BY` temp B-tree в SQLite
     * (ускоряет первичную сборку графа RAPTOR в несколько раз — сортировка по `stopSequence` идёт на уровне рейса в памяти).
     */
    private suspend fun loadStopTimesForServicesFast(serviceIds: List<String>): List<StopTimeEntity> =
        withContext(Dispatchers.IO) {
            if (serviceIds.isEmpty()) return@withContext emptyList()
            val db = databaseHolder.database.openHelper.readableDatabase
            val result = ArrayList<StopTimeEntity>(250_000)
            for (chunk in serviceIds.chunked(500)) {
                val placeholders = chunk.joinToString(",") { "?" }
                val sql = "SELECT st.trip_id, st.stop_sequence, st.stop_id, st.arrival_time_sec, st.departure_time_sec, st.is_on_demand " +
                    "FROM stop_times st INNER JOIN trips t ON st.trip_id = t.trip_id " +
                    "WHERE t.service_id IN ($placeholders)"
                db.query(sql, chunk.toTypedArray()).use { c ->
                    while (c.moveToNext()) {
                        result.add(
                            StopTimeEntity(
                                tripId = c.getString(0),
                                stopSequence = c.getInt(1),
                                stopId = c.getString(2),
                                arrivalTimeSec = c.getInt(3),
                                departureTimeSec = c.getInt(4),
                                isOnDemand = c.getInt(5)
                            )
                        )
                    }
                }
            }
            result
        }

    /**
     * Разрешает активные `service_id` на дату [date] НЕЗАВИСИМО для ZTM (автобусы/трамваи)
     * и для электричек (`rail_*`). Если у одного из источников календарь начинается завтра
     * или устарел, его fallback срабатывает независимо и не глушится вторым источником!
     */
    private suspend fun resolveActiveServicesForDate(
        date: LocalDate,
        dayTypeOverride: TimetableDayType? = null
    ): List<String> {
        val dateInt = ServiceCalendarResolver.toYyyymmdd(date)
        val calendars = dao.getAllCalendars()
        val calendarDates = dao.getCalendarDatesForDate(dateInt)
        val exactActive = ServiceCalendarResolver.resolveActiveServiceIds(
            date = date,
            calendars = calendars,
            calendarDatesForDate = calendarDates
        )

        val ztmActive = exactActive.filterNot { it.startsWith(RAIL_SERVICE_PREFIX) }.toMutableSet()
        val railActive = exactActive.filter { it.startsWith(RAIL_SERVICE_PREFIX) }.toMutableSet()

        if (ztmActive.isEmpty()) {
            val ztmCalendars = calendars.filterNot { it.serviceId.startsWith(RAIL_SERVICE_PREFIX) }
            val byDay = ztmCalendars.filter { cal ->
                when (dayTypeOverride) {
                    TimetableDayType.WEEKDAY -> cal.monday || cal.tuesday || cal.wednesday || cal.thursday || cal.friday
                    TimetableDayType.SATURDAY -> cal.saturday
                    TimetableDayType.SUNDAY -> cal.sunday
                    else -> matchesDayOfWeek(cal, date.dayOfWeek)
                }
            }.map { it.serviceId }
            ztmActive.addAll(byDay.ifEmpty { ztmCalendars.map { it.serviceId } })
        }

        if (railActive.isEmpty()) {
            val railCalendars = calendars.filter { it.serviceId.startsWith(RAIL_SERVICE_PREFIX) }
            val byCal = railCalendars.filter { cal ->
                when (dayTypeOverride) {
                    TimetableDayType.WEEKDAY -> cal.monday || cal.tuesday || cal.wednesday || cal.thursday || cal.friday
                    TimetableDayType.SATURDAY -> cal.saturday
                    TimetableDayType.SUNDAY -> cal.sunday
                    else -> matchesDayOfWeek(cal, date.dayOfWeek)
                }
            }.map { it.serviceId }
            railActive.addAll(byCal)
            if (railActive.isEmpty()) {
                for (delta in listOf(7L, -7L, 1L, -1L)) {
                    val probeDate = date.plusDays(delta)
                    val probeDates = dao.getCalendarDatesForDate(ServiceCalendarResolver.toYyyymmdd(probeDate))
                    val probeRail = probeDates
                        .filter { it.serviceId.startsWith(RAIL_SERVICE_PREFIX) && it.exceptionType == ServiceCalendarResolver.EXCEPTION_ADDED }
                        .map { it.serviceId }
                    if (probeRail.isNotEmpty()) {
                        railActive.addAll(probeRail)
                        break
                    }
                }
            }
        }

        return (ztmActive + railActive).toList()
    }

    /**
     * Возвращает только те маршруты, которые реально курсируют в указанный день [date].
     */
    suspend fun getAllRoutesSorted(date: LocalDate = LocalDate.now(WARSAW_ZONE)): List<RouteEntity> = withContext(Dispatchers.IO) {
        val activeServices = resolveActiveServicesForDate(date)

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
            allRoutes
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
        val today = LocalDate.now(WARSAW_ZONE)
        val targetDate = when (dayType) {
            TimetableDayType.TODAY -> today
            TimetableDayType.WEEKDAY -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY))
            TimetableDayType.SATURDAY -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            TimetableDayType.SUNDAY -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        }
        val activeServices = resolveActiveServicesForDate(targetDate, dayTypeOverride = dayType)

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
                    departures = deps,
                    latitude = stop.stopLat,
                    longitude = stop.stopLon
                )
            }

            StaticDirectionTimetable(
                directionId = dirId,
                headsign = headsign,
                stops = stopTimetables
            )
        }
    }

    /**
     * Вычисляет для конкретного сегмента поездки:
     * 1. Альтернативные линии, которые также напрямую связывают остановку посадки и остановку высадки.
     * 2. Примерный интервал отправлений (в минутах) на этом участке в окне ±45 минут.
     */
    fun computeLegInsight(
        boardingStopId: String,
        alightingStopId: String,
        routeShortName: String,
        departureTimeSec: Int,
        tripId: String? = null,
        dayOffset: Int = 0
    ): TransitLegInsight {
        val altGraph = cachedAlternateDayGraph?.second
        val g = when {
            dayOffset > 0 && altGraph != null -> altGraph
            tripId != null && altGraph != null && altGraph.tripIdToIndex.containsKey(tripId) -> altGraph
            else -> _graph.value ?: altGraph
        } ?: return TransitLegInsight()
        val localDepSec = if (dayOffset > 0) (departureTimeSec - dayOffset * 86_400).coerceAtLeast(0) else departureTimeSec
        val boardSiblings = g.findSiblingStopIndices(boardingStopId)
        val alightSiblings = g.findSiblingStopIndices(alightingStopId).toHashSet()
        if (boardSiblings.isEmpty() || alightSiblings.isEmpty()) return TransitLegInsight()

        val windowStart = localDepSec - 30 * 60
        val windowEnd = localDepSec + 60 * 60

        val altLines = LinkedHashSet<String>()
        val corridorDepartures = ArrayList<Int>(32)
        val visitedPatterns = HashSet<Int>()

        for (bStopIdx in boardSiblings) {
            val pStart = g.stopToPatternOffsets[bStopIdx]
            val pEnd = g.stopToPatternOffsets[bStopIdx + 1]
            for (idx in pStart until pEnd) {
                val p = g.stopToPatterns[idx]
                if (!visitedPatterns.add(p)) continue
                val bPos = g.stopToPatternPos[idx]
                val pStopCount = g.patternStopCount(p)

                var reachesTarget = false
                for (pos in (bPos + 1) until pStopCount) {
                    if (g.patternStopAt(p, pos) in alightSiblings) {
                        reachesTarget = true
                        break
                    }
                }
                if (!reachesTarget) continue

                val lineName = g.patternRouteShortName[p]
                val tStart = g.patternTripOffsets[p]
                val tEnd = g.patternTripOffsets[p + 1]
                var hasDepInWindow = false
                for (t in tStart until tEnd) {
                    val dep = g.tripDepartureAt(t, bPos)
                    if (dep in windowStart..windowEnd) {
                        corridorDepartures.add(dep)
                        hasDepInWindow = true
                    }
                }
                if (hasDepInWindow && !lineName.equals(routeShortName, ignoreCase = true)) {
                    altLines.add(lineName)
                }
            }
        }

        val sortedDeps = corridorDepartures.distinct().sorted()
        val headwayMin = if (sortedDeps.size >= 2) {
            val totalSpanSec = sortedDeps.last() - sortedDeps.first()
            val avgSec = totalSpanSec / (sortedDeps.size - 1)
            ((avgSec + 30) / 60).coerceAtLeast(1)
        } else {
            null
        }

        val sortedAlts = altLines.sortedWith(
            compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it })
        ).take(6)

        return TransitLegInsight(
            alternativeLines = sortedAlts,
            approxHeadwayMinutes = headwayMin
        )
    }

    /**
     * Быстро определяет, является ли линия трамвайной по текущему графу (с fallback на нумерацию Познани 1..20).
     */
    fun isRouteTram(routeShortName: String): Boolean {
        val g = _graph.value
        val norm = routeShortName.trim()
        if (g != null && norm.isNotEmpty()) {
            for (p in 0 until g.patternCount) {
                if (g.patternRouteShortName[p].equals(norm, ignoreCase = true)) {
                    return g.patternRouteType[p] == 0 || g.patternRouteType[p] == 900
                }
            }
        }
        val num = norm.toIntOrNull()
        return num != null && (num in 1..20 || num == 98)
    }

    /**
     * Возвращает направление (`headsign`) для конкретного `tripId` из графа в памяти за O(1) (для рендера всех машин на карте).
     */
    fun resolveQuickHeadsignAndType(
        tripId: String?,
        routeId: String?,
        label: String
    ): Triple<String, Boolean, String> {
        val g = _graph.value
        val fallbackLine = routeId?.trim()?.takeIf { it.isNotEmpty() }
            ?: label.substringBefore('/').trim().ifEmpty { "?" }

        if (g != null && !tripId.isNullOrBlank()) {
            val tripIdx = findTripIdxInGraph(g, tripId)
            if (tripIdx >= 0) {
                val pIdx = g.tripPatternIndex[tripIdx]
                val line = g.patternRouteShortName[pIdx].ifBlank { fallbackLine }
                val isTram = g.patternRouteType[pIdx] == 0 || g.patternRouteType[pIdx] == 900
                val headsign = g.patternHeadsign[pIdx].ifBlank {
                    val lastIdx = g.patternStopAt(pIdx, (g.patternStopCount(pIdx) - 1).coerceAtLeast(0))
                    g.stopNames[lastIdx]
                }
                return Triple(line, isTram, headsign)
            }
        }
        return Triple(fallbackLine, isRouteTram(fallbackLine), "")
    }

    /**
     * Полная информация о выбранном живом транспорте на карте:
     * линия, направление (куда едет), `shapeId` для полупрозрачной линии маршрута,
     * список остановок на трассе и текущая/следующая остановка.
     */
    fun resolveVehicleRouteSnapshot(
        tripId: String?,
        routeId: String?,
        label: String,
        currentStopId: String?,
        vehicleLat: Double,
        vehicleLon: Double,
        bearing: Float?
    ): VehicleRouteSnapshot? {
        val g = _graph.value ?: return null
        val fallbackLine = routeId?.trim()?.takeIf { it.isNotEmpty() }
            ?: label.substringBefore('/').trim().ifEmpty { "?" }

        var tripIdx = if (!tripId.isNullOrBlank()) findTripIdxInGraph(g, tripId) else -1
        var patternIdx = if (tripIdx >= 0) g.tripPatternIndex[tripIdx] else -1

        // Fallback: если tripId отсутствует в текущем графе, подбираем наиболее подходящий паттерн той же линии
        // по ближайшей остановке и совпадению азимута движения
        if (patternIdx < 0 && fallbackLine != "?") {
            var bestP = -1
            var bestScore = Double.MAX_VALUE
            for (p in 0 until g.patternCount) {
                if (!g.patternRouteShortName[p].equals(fallbackLine, ignoreCase = true)) continue
                val pStopCount = g.patternStopCount(p)
                if (pStopCount < 2) continue
                var nearestPos = 0
                var nearestDist = Double.MAX_VALUE
                for (pos in 0 until pStopCount) {
                    val sIdx = g.patternStopAt(p, pos)
                    val d = pl.poznan.offdojade.data.gtfs.GeoUtils.haversineMeters(
                        vehicleLat, vehicleLon, g.stopLats[sIdx], g.stopLons[sIdx]
                    )
                    if (d < nearestDist) {
                        nearestDist = d
                        nearestPos = pos
                    }
                }
                var penalty = nearestDist
                if (bearing != null && bearing > 0f) {
                    val aIdx = g.patternStopAt(p, nearestPos.coerceAtMost(pStopCount - 2))
                    val bIdx = g.patternStopAt(p, (nearestPos + 1).coerceAtMost(pStopCount - 1))
                    val segBearing = computeBearing(g.stopLats[aIdx], g.stopLons[aIdx], g.stopLats[bIdx], g.stopLons[bIdx])
                    val diff = kotlin.math.abs(bearing.toDouble() - segBearing) % 360.0
                    val ang = if (diff > 180.0) 360.0 - diff else diff
                    if (ang > 100.0) penalty += 5_000.0
                }
                if (penalty < bestScore) {
                    bestScore = penalty
                    bestP = p
                }
            }
            if (bestP >= 0) {
                patternIdx = bestP
                val tStart = g.patternTripOffsets[bestP]
                if (tStart < g.patternTripOffsets[bestP + 1]) {
                    tripIdx = tStart
                }
            }
        }

        if (patternIdx < 0) return null

        val pStopCount = g.patternStopCount(patternIdx)
        val stops = ArrayList<LiveRouteStopPoint>(pStopCount)
        var currentStopPos = -1
        var nearestPos = 0
        var nearestDist = Double.MAX_VALUE

        for (pos in 0 until pStopCount) {
            val sIdx = g.patternStopAt(patternIdx, pos)
            val sId = g.stopIds[sIdx]
            val sName = g.stopNames[sIdx]
            val lat = g.stopLats[sIdx]
            val lon = g.stopLons[sIdx]
            stops.add(LiveRouteStopPoint(stopId = sId, stopName = sName, latitude = lat, longitude = lon))
            if (currentStopId != null && sId == currentStopId) {
                currentStopPos = pos
            }
            val d = pl.poznan.offdojade.data.gtfs.GeoUtils.haversineMeters(vehicleLat, vehicleLon, lat, lon)
            if (d < nearestDist) {
                nearestDist = d
                nearestPos = pos
            }
        }

        val resolvedCurrentPos = if (currentStopPos >= 0) currentStopPos else nearestPos
        val currentStopName = stops.getOrNull(resolvedCurrentPos)?.stopName
        val nextStopName = stops.getOrNull(resolvedCurrentPos + 1)?.stopName
        val line = g.patternRouteShortName[patternIdx].ifBlank { fallbackLine }
        val isTram = g.patternRouteType[patternIdx] == 0 || g.patternRouteType[patternIdx] == 900
        val headsign = g.patternHeadsign[patternIdx].ifBlank { stops.lastOrNull()?.stopName.orEmpty() }
        val shapeId = if (tripIdx >= 0) g.tripShapeIds[tripIdx] else ""

        return VehicleRouteSnapshot(
            routeShortName = line,
            isTram = isTram,
            headsign = headsign,
            shapeId = shapeId,
            stops = stops,
            currentStopName = currentStopName,
            nextStopName = nextStopName
        )
    }

    private fun findTripIdxInGraph(g: RaptorData, tripId: String): Int {
        g.tripIdToIndex[tripId]?.let { return it }
        val core = tripId.substringAfter('_', "")
        if (core.isNotEmpty()) {
            for (i in 0 until g.tripCount) {
                if (g.tripIds[i].substringAfter('_', "") == core) return i
            }
        }
        return -1
    }

    private fun computeBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = kotlin.math.sin(dLon) * kotlin.math.cos(phi2)
        val x = kotlin.math.cos(phi1) * kotlin.math.sin(phi2) -
            kotlin.math.sin(phi1) * kotlin.math.cos(phi2) * kotlin.math.cos(dLon)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }
}

data class LiveRouteStopPoint(
    val stopId: String,
    val stopName: String,
    val latitude: Double,
    val longitude: Double
)

data class VehicleRouteSnapshot(
    val routeShortName: String,
    val isTram: Boolean,
    val headsign: String,
    val shapeId: String,
    val stops: List<LiveRouteStopPoint>,
    val currentStopName: String?,
    val nextStopName: String?
)

data class TransitLegInsight(
    val alternativeLines: List<String> = emptyList(),
    val approxHeadwayMinutes: Int? = null
)

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
    val departures: List<StaticDepartureEntry>,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

data class StaticDirectionTimetable(
    val directionId: Int,
    val headsign: String,
    val stops: List<StaticStopTimetable>
)

data class CachedRouteResults(
    val request: RouteSearchRequest,
    val tripSettings: pl.poznan.offdojade.domain.model.TripSettings,
    val journeys: List<TransitJourney>,
    val searchDurationMs: Long,
    val referenceNowSec: Int,
    val loadedDayOffset: Int,
    val maxDayOffset: Int,
    val noMoreEarlier: Boolean,
    val noMoreLater: Boolean,
    val originLat: Double,
    val originLon: Double
)
