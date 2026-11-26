package pl.poznan.offdojade.domain.routing

import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.gtfs.GeoUtils
import pl.poznan.offdojade.domain.model.CatchabilityInfo
import pl.poznan.offdojade.domain.model.CatchabilityStatus
import pl.poznan.offdojade.domain.model.JourneyAlternativeTag
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.LegStop
import pl.poznan.offdojade.domain.model.NearbyStopBoard
import pl.poznan.offdojade.domain.model.RoutePreference
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.domain.model.TripSettings
import pl.poznan.offdojade.domain.model.UpcomingDeparture
import pl.poznan.offdojade.domain.tariff.ZtmTicketCalculator
import kotlin.math.roundToInt

/**
 * Сверхбыстрый движок маршрутизации **RAPTOR (Round-Based Public Transit Routing)**.
 * Работает исключительно с примитивными массивами [RaptorData] в памяти (< 50 мс на поиск),
 * учитывает живые задержки GTFS-RT (`effective_time = scheduled_time + delay_seconds`),
 * пользовательские фильтры [TripSettings], разнообразные виды доезда без кругов через весь город
 * и временное окно (ближайшие 5, ранее отъехавшие, позже).
 */
class RaptorEngine {

    private data class OriginAccess(
        val stopIdx: Int,
        val walkSeconds: Int,
        val distanceMeters: Int
    )

    private data class ResolvedQuery(
        val originAccesses: List<OriginAccess>,
        val originLat: Double,
        val originLon: Double,
        val originName: String,
        val targetStopIndices: IntArray,
        val targetLat: Double,
        val targetLon: Double,
        val targetName: String?,
        /** Пеший доход от конечной остановки до адреса назначения (пусто, если цель — сама остановка) */
        val targetEgressMap: Map<Int, OriginAccess> = emptyMap()
    )

    /**
     * Поиск маршрутов от GPS-координат пользователя (пеший подход до остановок в радиусе 600 м)
     * до целевой остановки [targetStopId] (автоматически учитывает все платформы с тем же названием).
     */
    fun searchFromCoordinates(
        data: RaptorData,
        originLat: Double,
        originLon: Double,
        targetStopId: String,
        departureTimeSec: Int,
        tripDelays: Map<String, Int> = emptyMap(),
        maxTransfers: Int = AppConfig.RAPTOR_MAX_TRANSFERS,
        maxOriginWalkMeters: Double = AppConfig.ORIGIN_WALK_MAX_DISTANCE_METERS,
        tripSettings: TripSettings = TripSettings()
    ): List<TransitJourney> {
        val resolved = resolveCoordinateQuery(
            data = data,
            originLat = originLat,
            originLon = originLon,
            targetStopId = targetStopId,
            maxOriginWalkMeters = maxOriginWalkMeters,
            tripSettings = tripSettings
        ) ?: return emptyList()

        val effectiveMaxTransfers = minOf(maxTransfers, tripSettings.effectiveMaxTransfers)
        return searchDiverseAtTime(
            data = data,
            query = resolved,
            departureTimeSec = departureTimeSec,
            tripDelays = tripDelays,
            maxTransfers = effectiveMaxTransfers,
            tripSettings = tripSettings
        )
    }

    /**
     * Поиск маршрутов от остановки (Пункт А) [originStopId] до остановки (Пункт Б) [targetStopId].
     * Автоматически объединяет все платформы с одинаковым названием как для старта, так и для финиша.
     */
    fun searchFromStop(
        data: RaptorData,
        originStopId: String,
        targetStopId: String,
        departureTimeSec: Int,
        tripDelays: Map<String, Int> = emptyMap(),
        maxTransfers: Int = AppConfig.RAPTOR_MAX_TRANSFERS,
        tripSettings: TripSettings = TripSettings()
    ): List<TransitJourney> {
        val resolved = resolveStopQuery(
            data = data,
            originStopId = originStopId,
            targetStopId = targetStopId
        ) ?: return emptyList()

        val effectiveMaxTransfers = minOf(maxTransfers, tripSettings.effectiveMaxTransfers)
        return searchDiverseAtTime(
            data = data,
            query = resolved,
            departureTimeSec = departureTimeSec,
            tripDelays = tripDelays,
            maxTransfers = effectiveMaxTransfers,
            tripSettings = tripSettings
        )
    }

    /**
     * Поиск ближайших [limit] (по умолчанию 5) отправлений начиная с [departureTimeSec],
     * включая альтернативные виды доезда (например, прямой автобус и короткий трамвай с пересадкой).
     */
    fun searchUpcomingJourneys(
        data: RaptorData,
        originStopId: String?,
        originLat: Double,
        originLon: Double,
        targetStopId: String?,
        departureTimeSec: Int,
        tripDelays: Map<String, Int> = emptyMap(),
        tripSettings: TripSettings = TripSettings(),
        limit: Int = 5,
        excludeIds: Set<String> = emptySet(),
        originName: String = "GPS",
        targetLat: Double? = null,
        targetLon: Double? = null,
        targetName: String? = null
    ): List<TransitJourney> {
        val resolved = resolveGeneralQuery(
            data = data,
            originStopId = originStopId,
            originLat = originLat,
            originLon = originLon,
            originName = originName,
            targetStopId = targetStopId,
            targetLat = targetLat,
            targetLon = targetLon,
            targetName = targetName,
            tripSettings = tripSettings
        ) ?: return emptyList()

        val effectiveMaxTransfers = tripSettings.effectiveMaxTransfers
        val collected = LinkedHashMap<String, TransitJourney>()
        val seenTripSignatures = HashSet<String>()
        val maxWindowEnd = departureTimeSec + UPCOMING_SEARCH_HORIZON_SEC
        var cursorTime = departureTimeSec
        var iterations = 0

        while (collected.size < limit && cursorTime <= maxWindowEnd && iterations < MAX_TIMELINE_ITERATIONS) {
            iterations++
            val batch = searchDiverseAtTime(
                data = data,
                query = resolved,
                departureTimeSec = cursorTime,
                tripDelays = tripDelays,
                maxTransfers = effectiveMaxTransfers,
                tripSettings = tripSettings
            )
            if (batch.isEmpty()) {
                cursorTime += EMPTY_STEP_SEC
                continue
            }

            var minDepartureInBatch = Int.MAX_VALUE
            for (journey in batch) {
                if (journey.departureTimeSec < minDepartureInBatch) {
                    minDepartureInBatch = journey.departureTimeSec
                }
                val tripSig = journey.tripSignature()
                if (journey.id !in excludeIds && tripSig !in seenTripSignatures) {
                    seenTripSignatures.add(tripSig)
                    collected[journey.id] = journey
                }
            }

            val nextCursor = maxOf(cursorTime + 60, minDepartureInBatch + 60)
            cursorTime = nextCursor
        }

        return collected.values
            .sortedWith(
                compareBy<TransitJourney> { it.departureTimeSec }
                    .thenBy { scoreJourney(it, tripSettings) }
            )
            .take(limit)
    }

    /**
     * Поиск уже отъехавших маршрутов (до [beforeDepartureSec]) для подгрузки при прокрутке вверх.
     * Возвращает до [limit] маршрутов, отсортированных хронологически (от более ранних к более поздним).
     */
    fun searchEarlierJourneys(
        data: RaptorData,
        originStopId: String?,
        originLat: Double,
        originLon: Double,
        targetStopId: String?,
        beforeDepartureSec: Int,
        tripDelays: Map<String, Int> = emptyMap(),
        tripSettings: TripSettings = TripSettings(),
        limit: Int = 5,
        excludeIds: Set<String> = emptySet(),
        originName: String = "GPS",
        targetLat: Double? = null,
        targetLon: Double? = null,
        targetName: String? = null
    ): List<TransitJourney> {
        val resolved = resolveGeneralQuery(
            data = data,
            originStopId = originStopId,
            originLat = originLat,
            originLon = originLon,
            originName = originName,
            targetStopId = targetStopId,
            targetLat = targetLat,
            targetLon = targetLon,
            targetName = targetName,
            tripSettings = tripSettings
        ) ?: return emptyList()

        val effectiveMaxTransfers = tripSettings.effectiveMaxTransfers
        val collected = LinkedHashMap<String, TransitJourney>()
        val seenTripSignatures = HashSet<String>()
        val minSearchTime = (beforeDepartureSec - EARLIER_SEARCH_HORIZON_SEC).coerceAtLeast(0)

        var step = 1
        while (collected.size < limit && step <= MAX_EARLIER_STEPS) {
            val probeTime = (beforeDepartureSec - step * EARLIER_STEP_SEC).coerceAtLeast(minSearchTime)
            val batch = searchDiverseAtTime(
                data = data,
                query = resolved,
                departureTimeSec = probeTime,
                tripDelays = tripDelays,
                maxTransfers = effectiveMaxTransfers,
                tripSettings = tripSettings
            )
            for (journey in batch) {
                if (journey.departureTimeSec < beforeDepartureSec && journey.id !in excludeIds) {
                    val tripSig = journey.tripSignature()
                    if (tripSig !in seenTripSignatures) {
                        seenTripSignatures.add(tripSig)
                        collected[journey.id] = journey
                    }
                }
            }
            if (probeTime <= minSearchTime) break
            step++
        }

        // Берем ближайшие к beforeDepartureSec (самые свежие из уже ушедших), и сортируем по возрастанию времени
        return collected.values
            .sortedByDescending { it.departureTimeSec }
            .take(limit)
            .sortedBy { it.departureTimeSec }
    }

    /**
     * Выполняет основной проход RAPTOR + дополнительные модальные проходы (только трамваи / только автобусы)
     * для выдачи разнообразных коротких вариантов доезда (например, Гурчин → Дембец: прямой автобус и трамваи с пересадкой),
     * отсеивая неразумные объезды через весь город.
     */
    private fun searchDiverseAtTime(
        data: RaptorData,
        query: ResolvedQuery,
        departureTimeSec: Int,
        tripDelays: Map<String, Int>,
        maxTransfers: Int,
        tripSettings: TripSettings
    ): List<TransitJourney> {
        val candidates = ArrayList<TransitJourney>(8)

        // 1. Основной проход со всеми разрешёнными видами транспорта
        val primary = executeRaptor(
            data = data,
            query = query,
            departureTimeSec = departureTimeSec,
            tripDelays = tripDelays,
            maxTransfers = maxTransfers,
            tripSettings = tripSettings,
            alternativeTag = JourneyAlternativeTag.STANDARD
        )
        candidates.addAll(primary)

        // 2. Разнообразие видов транспорта: если разрешены и трамваи, и автобусы — пробуем отдельные проходы
        val tramsEnabled = TransportMode.TRAM in tripSettings.enabledTransportModes
        val busesEnabled = TransportMode.BUS in tripSettings.enabledTransportModes && !tripSettings.excludeBuses

        if (tramsEnabled && busesEnabled) {
            val hasTramOnly = candidates.any { j -> j.transitLegs.all { it.isTram } }
            val hasBusOnly = candidates.any { j -> j.transitLegs.all { !it.isTram } }

            if (!hasTramOnly) {
                val tramSettings = tripSettings.copy(
                    enabledTransportModes = setOf(TransportMode.TRAM, TransportMode.NIGHT)
                        .intersect(tripSettings.enabledTransportModes),
                    excludeBuses = true
                )
                val tramAlt = executeRaptor(
                    data = data,
                    query = query,
                    departureTimeSec = departureTimeSec,
                    tripDelays = tripDelays,
                    maxTransfers = minOf(maxTransfers, 2),
                    tripSettings = tramSettings,
                    alternativeTag = JourneyAlternativeTag.TRAM_ONLY
                )
                candidates.addAll(tramAlt)
            }

            if (!hasBusOnly) {
                val busSettings = tripSettings.copy(
                    enabledTransportModes = setOf(TransportMode.BUS, TransportMode.NIGHT)
                        .intersect(tripSettings.enabledTransportModes)
                )
                val busAlt = executeRaptor(
                    data = data,
                    query = query,
                    departureTimeSec = departureTimeSec,
                    tripDelays = tripDelays,
                    maxTransfers = minOf(maxTransfers, 1),
                    tripSettings = busSettings,
                    alternativeTag = JourneyAlternativeTag.BUS_ONLY
                )
                candidates.addAll(busAlt)
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // 3. Дедупликация по набору рейсов
        val uniqueByTrips = LinkedHashMap<String, TransitJourney>()
        for (j in candidates) {
            val sig = j.tripSignature()
            val existing = uniqueByTrips[sig]
            if (existing == null || j.arrivalTimeSec < existing.arrivalTimeSec) {
                uniqueByTrips[sig] = j
            }
        }

        val uniqueList = uniqueByTrips.values.toList()
        val directDistMeters = GeoUtils.haversineMeters(
            query.originLat, query.originLon,
            query.targetLat, query.targetLon
        )

        // 4. Фильтр против кругов через весь город (Anti-Detour)
        val filtered = filterOutDetours(
            journeys = uniqueList,
            directDistMeters = directDistMeters
        )

        // 5. Тегируем прямые маршруты и сортируем с учетом профиля поездки (Удобно / Лучшее / Быстрое)
        val tagged = filtered.map { j ->
            if (j.alternativeTag == JourneyAlternativeTag.STANDARD && j.transferCount == 0 && filtered.size > 1) {
                j.copy(alternativeTag = JourneyAlternativeTag.DIRECT)
            } else {
                j
            }
        }

        return tagged.sortedBy { scoreJourney(it, tripSettings) }
    }

    /**
     * Отсеивает маршруты-объезды через весь город:
     * сравнивает пройденную географическую дистанцию с прямой дистанцией (Хаверсин)
     * и длительность поездки с лучшим вариантом.
     */
    private fun filterOutDetours(
        journeys: List<TransitJourney>,
        directDistMeters: Double
    ): List<TransitJourney> {
        if (journeys.size <= 1) return journeys

        val bestDurationSec = journeys.minOf { (it.arrivalTimeSec - it.departureTimeSec).coerceAtLeast(60) }
        val bestArrivalSec = journeys.minOf { it.arrivalTimeSec }
        val hasDirectOption = journeys.any { it.transferCount == 0 }

        val kept = journeys.filter { j ->
            val durationSec = (j.arrivalTimeSec - j.departureTimeSec).coerceAtLeast(60)
            val travelledDist = computeTravelledDistanceMeters(j)

            // Всегда оставляем абсолютно самый быстрый по прибытию вариант (если он сам не безумный)
            val isFastestArrival = j.arrivalTimeSec == bestArrivalSec
            if (isFastestArrival && (directDistMeters < 1000.0 || travelledDist <= directDistMeters * 3.0)) {
                return@filter true
            }

            // Проверка 1: длительность поездки не должна быть чрезмерно больше лучшего варианта
            val maxAllowedDuration = (bestDurationSec * MAX_DETOUR_DURATION_RATIO).toInt() + MAX_DETOUR_EXTRA_SEC
            if (durationSec > maxAllowedDuration) {
                return@filter false
            }

            // Проверка 2: географический крюк (особенно для вариантов с пересадками при наличии прямого маршрута)
            if (directDistMeters >= MIN_DIST_FOR_DETOUR_CHECK_METERS) {
                val maxRatio = when {
                    j.alternativeTag == JourneyAlternativeTag.TRAM_ONLY && j.transferCount > 0 && hasDirectOption ->
                        MAX_MODAL_DETOUR_RATIO
                    j.alternativeTag == JourneyAlternativeTag.BUS_ONLY && j.transferCount > 0 && hasDirectOption ->
                        MAX_MODAL_DETOUR_RATIO
                    else -> MAX_DETOUR_DISTANCE_RATIO
                }
                if (travelledDist > directDistMeters * maxRatio) {
                    return@filter false
                }
            }

            true
        }

        return kept.ifEmpty { listOf(journeys.minBy { it.arrivalTimeSec }) }
    }

    private fun computeTravelledDistanceMeters(journey: TransitJourney): Double {
        var total = 0.0
        for (leg in journey.legs) {
            when (leg) {
                is JourneyLeg.WalkLeg -> total += leg.distanceMeters
                is JourneyLeg.TransitLeg -> {
                    val stops = leg.stops
                    for (i in 0 until stops.lastIndex) {
                        total += GeoUtils.haversineMeters(
                            stops[i].latitude, stops[i].longitude,
                            stops[i + 1].latitude, stops[i + 1].longitude
                        )
                    }
                }
            }
        }
        return total
    }

    private fun scoreJourney(journey: TransitJourney, settings: TripSettings): Int {
        val durationSec = (journey.arrivalTimeSec - journey.departureTimeSec).coerceAtLeast(0)
        val walkMeters = journey.totalWalkMeters
        val preferredMatches = journey.transitLegs.count { settings.isLinePreferred(it.routeShortName) }
        val preferredBonus = preferredMatches * 180
        val avoidTransfersPenalty = if (settings.avoidTransfers) journey.transferCount * 600 else 0

        return when (settings.routePreference) {
            RoutePreference.FASTEST ->
                journey.arrivalTimeSec + avoidTransfersPenalty - preferredBonus / 2

            RoutePreference.BEST ->
                durationSec +
                    journey.transferCount * 240 +
                    (walkMeters / 2) +
                    avoidTransfersPenalty -
                    preferredBonus

            RoutePreference.CONVENIENT ->
                durationSec +
                    journey.transferCount * 540 +
                    (walkMeters * 2) +
                    avoidTransfersPenalty -
                    preferredBonus
        }
    }

    private fun findNearbyAccesses(
        data: RaptorData,
        lat: Double,
        lon: Double,
        maxMeters: Double,
        tripSettings: TripSettings
    ): List<OriginAccess> {
        val accesses = ArrayList<OriginAccess>(16)
        for (i in 0 until data.stopCount) {
            if (!tripSettings.isZoneAllowed(data.stopZones[i])) continue
            val dist = GeoUtils.haversineMeters(lat, lon, data.stopLats[i], data.stopLons[i])
            if (dist <= maxMeters) {
                accesses.add(
                    OriginAccess(
                        stopIdx = i,
                        walkSeconds = GeoUtils.walkingTimeSeconds(dist),
                        distanceMeters = dist.roundToInt()
                    )
                )
            }
        }
        if (accesses.isEmpty() && data.stopCount > 0) {
            val nearest = (0 until data.stopCount)
                .filter { tripSettings.isZoneAllowed(data.stopZones[it]) }
                .ifEmpty { (0 until data.stopCount).toList() }
                .map { i ->
                    val dist = GeoUtils.haversineMeters(lat, lon, data.stopLats[i], data.stopLons[i])
                    OriginAccess(i, GeoUtils.walkingTimeSeconds(dist), dist.roundToInt())
                }
                .sortedBy { it.distanceMeters }
                .take(3)
            accesses.addAll(nearest)
        }
        return accesses
    }

    private fun resolveGeneralQuery(
        data: RaptorData,
        originStopId: String?,
        originLat: Double,
        originLon: Double,
        originName: String,
        targetStopId: String?,
        targetLat: Double?,
        targetLon: Double?,
        targetName: String?,
        tripSettings: TripSettings
    ): ResolvedQuery? {
        val originAccesses: List<OriginAccess>
        val resolvedOriginLat: Double
        val resolvedOriginLon: Double

        if (!originStopId.isNullOrBlank()) {
            val originIndices = data.findSiblingStopIndices(originStopId)
            if (originIndices.isEmpty()) return null
            originAccesses = originIndices.map { OriginAccess(it, 0, 0) }
            resolvedOriginLat = data.stopLats[originIndices[0]]
            resolvedOriginLon = data.stopLons[originIndices[0]]
        } else {
            originAccesses = findNearbyAccesses(
                data = data,
                lat = originLat,
                lon = originLon,
                maxMeters = AppConfig.ORIGIN_WALK_MAX_DISTANCE_METERS,
                tripSettings = tripSettings
            )
            if (originAccesses.isEmpty()) return null
            resolvedOriginLat = originLat
            resolvedOriginLon = originLon
        }

        val targetStopIndices: IntArray
        val resolvedTargetLat: Double
        val resolvedTargetLon: Double
        val targetEgressMap: Map<Int, OriginAccess>

        if (!targetStopId.isNullOrBlank()) {
            targetStopIndices = data.findSiblingStopIndices(targetStopId)
            if (targetStopIndices.isEmpty()) return null
            resolvedTargetLat = data.stopLats[targetStopIndices[0]]
            resolvedTargetLon = data.stopLons[targetStopIndices[0]]
            targetEgressMap = emptyMap()
        } else if (targetLat != null && targetLon != null) {
            val egressAccesses = findNearbyAccesses(
                data = data,
                lat = targetLat,
                lon = targetLon,
                maxMeters = AppConfig.ORIGIN_WALK_MAX_DISTANCE_METERS,
                tripSettings = tripSettings
            )
            if (egressAccesses.isEmpty()) return null
            targetStopIndices = egressAccesses.map { it.stopIdx }.toIntArray()
            resolvedTargetLat = targetLat
            resolvedTargetLon = targetLon
            targetEgressMap = egressAccesses.associateBy { it.stopIdx }
        } else {
            return null
        }

        val targetSet = targetStopIndices.toHashSet()
        if (!originStopId.isNullOrBlank() && !targetStopId.isNullOrBlank() && originAccesses.any { it.stopIdx in targetSet }) {
            return null
        }

        return ResolvedQuery(
            originAccesses = originAccesses,
            originLat = resolvedOriginLat,
            originLon = resolvedOriginLon,
            originName = originName,
            targetStopIndices = targetStopIndices,
            targetLat = resolvedTargetLat,
            targetLon = resolvedTargetLon,
            targetName = targetName,
            targetEgressMap = targetEgressMap
        )
    }

    private fun resolveCoordinateQuery(
        data: RaptorData,
        originLat: Double,
        originLon: Double,
        targetStopId: String,
        maxOriginWalkMeters: Double,
        tripSettings: TripSettings
    ): ResolvedQuery? {
        val targetIndices = data.findSiblingStopIndices(targetStopId)
        if (targetIndices.isEmpty()) return null
        val accesses = findNearbyAccesses(data, originLat, originLon, maxOriginWalkMeters, tripSettings)
        val firstTarget = targetIndices[0]
        return ResolvedQuery(
            originAccesses = accesses,
            originLat = originLat,
            originLon = originLon,
            originName = "GPS",
            targetStopIndices = targetIndices,
            targetLat = data.stopLats[firstTarget],
            targetLon = data.stopLons[firstTarget],
            targetName = null
        )
    }

    private fun resolveStopQuery(
        data: RaptorData,
        originStopId: String,
        targetStopId: String
    ): ResolvedQuery? {
        val originIndices = data.findSiblingStopIndices(originStopId)
        val targetIndices = data.findSiblingStopIndices(targetStopId)
        if (originIndices.isEmpty() || targetIndices.isEmpty()) return null

        val targetSet = targetIndices.toHashSet()
        if (originIndices.any { it in targetSet }) return null

        val accesses = originIndices.map { idx ->
            OriginAccess(stopIdx = idx, walkSeconds = 0, distanceMeters = 0)
        }
        val firstOrigin = originIndices[0]
        val firstTarget = targetIndices[0]
        return ResolvedQuery(
            originAccesses = accesses,
            originLat = data.stopLats[firstOrigin],
            originLon = data.stopLons[firstOrigin],
            originName = data.stopNames[firstOrigin],
            targetStopIndices = targetIndices,
            targetLat = data.stopLats[firstTarget],
            targetLon = data.stopLons[firstTarget],
            targetName = null
        )
    }

    /**
     * Ближайшие остановки и их онлайн-табло с учетом задержек GTFS-RT.
     * Все платформы с одинаковым названием (например, 6 перронов Rondo Kaponiera)
     * объединяются в одну карточку с общим списком ближайших отправлений.
     */
    fun findNearbyStopBoards(
        data: RaptorData,
        userLat: Double,
        userLon: Double,
        currentTimeSec: Int,
        tripDelays: Map<String, Int> = emptyMap(),
        maxStops: Int = 3,
        departuresPerStop: Int = 5
    ): List<NearbyStopBoard> {
        if (data.stopCount == 0) return emptyList()

        data class GroupCandidate(
            val normalizedName: String,
            val indices: IntArray,
            val nearestIdx: Int,
            val minDistanceMeters: Double
        )

        val candidates = ArrayList<GroupCandidate>(data.stopIndicesByNormalizedName.size)
        for ((normName, indices) in data.stopIndicesByNormalizedName) {
            var bestIdx = indices[0]
            var bestDist = Double.MAX_VALUE
            for (i in indices) {
                val dist = GeoUtils.haversineMeters(userLat, userLon, data.stopLats[i], data.stopLons[i])
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = i
                }
            }
            candidates.add(GroupCandidate(normName, indices, bestIdx, bestDist))
        }
        candidates.sortBy { it.minDistanceMeters }

        val result = ArrayList<NearbyStopBoard>(maxStops)
        for (group in candidates.take(maxStops)) {
            val mergedDepartures = ArrayList<UpcomingDeparture>(departuresPerStop * group.indices.size)
            for (stopIdx in group.indices) {
                mergedDepartures.addAll(
                    getUpcomingDepartures(
                        data = data,
                        stopIdx = stopIdx,
                        currentTimeSec = currentTimeSec,
                        tripDelays = tripDelays,
                        limit = departuresPerStop
                    )
                )
            }
            mergedDepartures.sortBy { it.effectiveTimeSec }
            val uniqueDepartures = mergedDepartures
                .distinctBy { it.tripId }
                .take(departuresPerStop)

            val rep = group.nearestIdx
            result.add(
                NearbyStopBoard(
                    stopId = data.stopIds[rep],
                    stopName = data.stopNames[rep],
                    zoneId = data.stopZones[rep],
                    platformCode = data.stopPlatforms[rep],
                    distanceMeters = group.minDistanceMeters.roundToInt(),
                    walkTimeSeconds = GeoUtils.walkingTimeSeconds(group.minDistanceMeters),
                    departures = uniqueDepartures,
                    platformCount = group.indices.size
                )
            )
        }
        return result
    }

    /**
     * Ближайшие отправления с остановки [stopIdx] за следующие 90 минут с учетом задержек GTFS-RT.
     */
    fun getUpcomingDepartures(
        data: RaptorData,
        stopIdx: Int,
        currentTimeSec: Int,
        tripDelays: Map<String, Int>,
        limit: Int = 5
    ): List<UpcomingDeparture> {
        val windowEnd = currentTimeSec + 90 * 60
        val list = ArrayList<UpcomingDeparture>(16)

        val pStart = data.stopToPatternOffsets[stopIdx]
        val pEnd = data.stopToPatternOffsets[stopIdx + 1]
        for (idx in pStart until pEnd) {
            val patternIdx = data.stopToPatterns[idx]
            val pos = data.stopToPatternPos[idx]
            val pStopCount = data.patternStopCount(patternIdx)
            if (pos >= pStopCount - 1) continue

            val tStart = data.patternTripOffsets[patternIdx]
            val tEnd = data.patternTripOffsets[patternIdx + 1]
            for (tripIdx in tStart until tEnd) {
                val scheduledDep = data.tripDepartureAt(tripIdx, pos)
                val delaySec = tripDelays[data.tripIds[tripIdx]] ?: 0
                val effectiveDep = scheduledDep + delaySec
                if (effectiveDep in currentTimeSec..windowEnd) {
                    val mins = ((effectiveDep - currentTimeSec) / 60).coerceAtLeast(0)
                    list.add(
                        UpcomingDeparture(
                            tripId = data.tripIds[tripIdx],
                            routeShortName = data.patternRouteShortName[patternIdx],
                            routeType = data.patternRouteType[patternIdx],
                            routeColorHex = data.patternRouteColor[patternIdx],
                            headsign = data.patternHeadsign[patternIdx],
                            scheduledTimeSec = scheduledDep,
                            delaySeconds = delaySec,
                            effectiveTimeSec = effectiveDep,
                            minutesUntilDeparture = mins
                        )
                    )
                }
            }
        }

        list.sortBy { it.effectiveTimeSec }
        return list.take(limit)
    }

    private fun executeRaptor(
        data: RaptorData,
        query: ResolvedQuery,
        departureTimeSec: Int,
        tripDelays: Map<String, Int>,
        maxTransfers: Int,
        tripSettings: TripSettings,
        alternativeTag: JourneyAlternativeTag
    ): List<TransitJourney> {
        val originAccesses = query.originAccesses
        val targetStopIndices = query.targetStopIndices
        if (originAccesses.isEmpty() || targetStopIndices.isEmpty() || data.stopCount == 0) return emptyList()

        val startNano = System.nanoTime()
        val rounds = maxTransfers + 1
        val stopCount = data.stopCount
        val patternCount = data.patternCount
        val minTransferSec = maxOf(MIN_SAME_STOP_TRANSFER_SEC, tripSettings.minTransferSeconds)

        val isTargetStop = BooleanArray(stopCount)
        for (tIdx in targetStopIndices) {
            if (tIdx in 0 until stopCount) isTargetStop[tIdx] = true
        }

        // Предвычисляем разрешённые паттерны и остановки (зоны)
        val allowedPattern = BooleanArray(patternCount) { p ->
            tripSettings.isLineAllowed(
                routeType = data.patternRouteType[p],
                shortName = data.patternRouteShortName[p]
            )
        }
        val allowedStop = BooleanArray(stopCount) { s ->
            isTargetStop[s] || tripSettings.isZoneAllowed(data.stopZones[s])
        }
        for (access in originAccesses) {
            if (access.stopIdx in 0 until stopCount) {
                allowedStop[access.stopIdx] = true
            }
        }

        val tau = Array(rounds + 1) { IntArray(stopCount) { INF } }
        val bestArrival = IntArray(stopCount) { INF }

        val parentType = Array(rounds + 1) { IntArray(stopCount) { PARENT_NONE } }
        val parentPrevStop = Array(rounds + 1) { IntArray(stopCount) { -1 } }
        val parentTrip = Array(rounds + 1) { IntArray(stopCount) { -1 } }
        val parentBoardPos = Array(rounds + 1) { IntArray(stopCount) { -1 } }
        val parentAlightPos = Array(rounds + 1) { IntArray(stopCount) { -1 } }
        val parentWalkDist = Array(rounds + 1) { IntArray(stopCount) { 0 } }

        var markedStops = BooleanArray(stopCount)
        val originAccessMap = HashMap<Int, OriginAccess>(originAccesses.size * 2)

        for (access in originAccesses) {
            val arrival = departureTimeSec + access.walkSeconds
            if (arrival < tau[0][access.stopIdx]) {
                tau[0][access.stopIdx] = arrival
                bestArrival[access.stopIdx] = arrival
                parentType[0][access.stopIdx] = PARENT_ORIGIN
                parentWalkDist[0][access.stopIdx] = access.distanceMeters
                markedStops[access.stopIdx] = true
                originAccessMap[access.stopIdx] = access
            }
        }

        if (originAccesses.all { it.walkSeconds == 0 }) {
            for (access in originAccesses) {
                val startStop = access.stopIdx
                val fpStart = data.footpathOffsets[startStop]
                val fpEnd = data.footpathOffsets[startStop + 1]
                for (f in fpStart until fpEnd) {
                    val toStop = data.footpathToStop[f]
                    if (!allowedStop[toStop]) continue
                    val arr = departureTimeSec + data.footpathWalkSec[f]
                    if (arr < tau[0][toStop]) {
                        tau[0][toStop] = arr
                        bestArrival[toStop] = arr
                        parentType[0][toStop] = PARENT_FOOTPATH
                        parentPrevStop[0][toStop] = startStop
                        parentWalkDist[0][toStop] = data.footpathDistanceMeters[f]
                        markedStops[toStop] = true
                    }
                }
            }
        }

        val patternEarliestPos = IntArray(patternCount) { INF }
        val activePatterns = IntArray(patternCount)
        var activePatternCount: Int

        for (k in 1..rounds) {
            activePatternCount = 0
            for (s in 0 until stopCount) {
                if (!markedStops[s]) continue
                val pStart = data.stopToPatternOffsets[s]
                val pEnd = data.stopToPatternOffsets[s + 1]
                for (idx in pStart until pEnd) {
                    val p = data.stopToPatterns[idx]
                    if (!allowedPattern[p]) continue
                    val pos = data.stopToPatternPos[idx]
                    if (patternEarliestPos[p] == INF) {
                        activePatterns[activePatternCount++] = p
                        patternEarliestPos[p] = pos
                    } else if (pos < patternEarliestPos[p]) {
                        patternEarliestPos[p] = pos
                    }
                }
            }

            if (activePatternCount == 0) break

            val nextMarked = BooleanArray(stopCount)
            var targetBest = INF
            for (tIdx in targetStopIndices) {
                if (bestArrival[tIdx] < targetBest) targetBest = bestArrival[tIdx]
            }

            for (apIdx in 0 until activePatternCount) {
                val p = activePatterns[apIdx]
                val startPos = patternEarliestPos[p]
                patternEarliestPos[p] = INF

                val pStopCount = data.patternStopCount(p)
                val tripStart = data.patternTripOffsets[p]
                val tripEnd = data.patternTripOffsets[p + 1]
                if (tripStart >= tripEnd) continue

                var currentTrip = -1
                var currentTripDelay = 0
                var boardStop = -1
                var boardPos = -1

                for (pos in startPos until pStopCount) {
                    val stop = data.patternStopAt(p, pos)

                    // Если остановка в исключённой зоне — нельзя через неё ехать/выходить
                    if (!allowedStop[stop]) {
                        currentTrip = -1
                        continue
                    }

                    if (currentTrip != -1) {
                        val effectiveArr = data.tripArrivalAt(currentTrip, pos) + currentTripDelay
                        if (effectiveArr < bestArrival[stop] && effectiveArr < targetBest) {
                            tau[k][stop] = effectiveArr
                            bestArrival[stop] = effectiveArr
                            parentType[k][stop] = PARENT_TRANSIT
                            parentPrevStop[k][stop] = boardStop
                            parentTrip[k][stop] = currentTrip
                            parentBoardPos[k][stop] = boardPos
                            parentAlightPos[k][stop] = pos
                            nextMarked[stop] = true
                            if (isTargetStop[stop]) {
                                targetBest = effectiveArr
                            }
                        }
                    }

                    val prevArrAtStop = tau[k - 1][stop]
                    if (prevArrAtStop != INF) {
                        val transferBuffer = if (k > 1) {
                            // Если пришли пешком по footpath, время пересадки уже учтено в footpathWalkSec,
                            // иначе добавляем минимальное время пересадки на той же остановке
                            if (parentType[k - 1][stop] == PARENT_FOOTPATH) 0 else minTransferSec
                        } else {
                            0
                        }
                        val minBoardTime = prevArrAtStop + transferBuffer
                        val candidateTrip = findEarliestTrip(
                            data = data,
                            tripStart = tripStart,
                            tripEnd = tripEnd,
                            pos = pos,
                            minDepartureSec = minBoardTime,
                            tripDelays = tripDelays,
                            lowFloorOnly = tripSettings.lowFloorOnly
                        )
                        if (candidateTrip != -1) {
                            val candDelay = tripDelays[data.tripIds[candidateTrip]] ?: 0
                            val candDep = data.tripDepartureAt(candidateTrip, pos) + candDelay
                            val curDep = if (currentTrip != -1) {
                                data.tripDepartureAt(currentTrip, pos) + currentTripDelay
                            } else {
                                INF
                            }
                            if (currentTrip == -1 || candDep < curDep) {
                                currentTrip = candidateTrip
                                currentTripDelay = candDelay
                                boardStop = stop
                                boardPos = pos
                            }
                        }
                    }
                }
            }

            // Пешие пересадки (Footpaths) с учётом минимального времени пересадки
            val footpathUpdated = BooleanArray(stopCount)
            for (s in 0 until stopCount) {
                if (!nextMarked[s]) continue
                val baseTime = tau[k][s]
                if (baseTime == INF) continue

                val fpStart = data.footpathOffsets[s]
                val fpEnd = data.footpathOffsets[s + 1]
                for (f in fpStart until fpEnd) {
                    val toStop = data.footpathToStop[f]
                    if (!allowedStop[toStop]) continue
                    val transferTime = if (isTargetStop[toStop]) {
                        data.footpathWalkSec[f]
                    } else {
                        maxOf(data.footpathWalkSec[f], tripSettings.minTransferSeconds)
                    }
                    val walkArr = baseTime + transferTime
                    if (walkArr < bestArrival[toStop] && walkArr < targetBest) {
                        tau[k][toStop] = walkArr
                        bestArrival[toStop] = walkArr
                        parentType[k][toStop] = PARENT_FOOTPATH
                        parentPrevStop[k][toStop] = s
                        parentWalkDist[k][toStop] = data.footpathDistanceMeters[f]
                        footpathUpdated[toStop] = true
                        if (isTargetStop[toStop]) {
                            targetBest = walkArr
                        }
                    }
                }
            }

            for (s in 0 until stopCount) {
                if (footpathUpdated[s]) nextMarked[s] = true
            }
            markedStops = nextMarked
        }

        val elapsedMs = (System.nanoTime() - startNano) / 1_000_000L

        val journeys = ArrayList<TransitJourney>(rounds)
        var bestSeenArrival = INF
        for (k in 1..rounds) {
            var bestTargetInRound = -1
            var bestArrInRound = INF
            for (tIdx in targetStopIndices) {
                val arrAtStop = tau[k][tIdx]
                if (arrAtStop >= INF) continue
                val egressWalk = query.targetEgressMap[tIdx]?.walkSeconds ?: 0
                val totalArr = arrAtStop + egressWalk
                if (totalArr < bestArrInRound) {
                    bestArrInRound = totalArr
                    bestTargetInRound = tIdx
                }
            }
            if (bestTargetInRound != -1 && bestArrInRound < bestSeenArrival) {
                bestSeenArrival = bestArrInRound
                val journey = reconstructJourney(
                    data = data,
                    query = query,
                    round = k,
                    targetStopIdx = bestTargetInRound,
                    departureTimeSec = departureTimeSec,
                    tripDelays = tripDelays,
                    tau = tau,
                    parentType = parentType,
                    parentPrevStop = parentPrevStop,
                    parentTrip = parentTrip,
                    parentBoardPos = parentBoardPos,
                    parentAlightPos = parentAlightPos,
                    parentWalkDist = parentWalkDist,
                    originAccessMap = originAccessMap,
                    searchDurationMs = elapsedMs,
                    alternativeTag = alternativeTag,
                    isUlgowyTicket = tripSettings.isUlgowyTicket
                )
                if (journey != null) {
                    journeys.add(journey)
                }
            }
        }

        return journeys.sortedBy { it.arrivalTimeSec }
    }

    private fun findEarliestTrip(
        data: RaptorData,
        tripStart: Int,
        tripEnd: Int,
        pos: Int,
        minDepartureSec: Int,
        tripDelays: Map<String, Int>,
        lowFloorOnly: Boolean
    ): Int {
        val searchFloor = minDepartureSec - MAX_EXPECTED_DELAY_SEC
        var lo = tripStart
        var hi = tripEnd
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (data.tripDepartureAt(mid, pos) < searchFloor) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }

        var bestTrip = -1
        var bestEffectiveDep = INF
        for (t in lo until tripEnd) {
            val scheduled = data.tripDepartureAt(t, pos)
            if (scheduled > bestEffectiveDep + MAX_EXPECTED_EARLY_SEC) {
                break
            }
            if (lowFloorOnly && data.tripWheelchairAccessible[t] != 1) {
                continue
            }
            val delay = tripDelays[data.tripIds[t]] ?: 0
            val effective = scheduled + delay
            if (effective >= minDepartureSec && effective < bestEffectiveDep) {
                bestEffectiveDep = effective
                bestTrip = t
            }
        }
        return bestTrip
    }

    private fun reconstructJourney(
        data: RaptorData,
        query: ResolvedQuery,
        round: Int,
        targetStopIdx: Int,
        departureTimeSec: Int,
        tripDelays: Map<String, Int>,
        tau: Array<IntArray>,
        parentType: Array<IntArray>,
        parentPrevStop: Array<IntArray>,
        parentTrip: Array<IntArray>,
        parentBoardPos: Array<IntArray>,
        parentAlightPos: Array<IntArray>,
        parentWalkDist: Array<IntArray>,
        originAccessMap: Map<Int, OriginAccess>,
        searchDurationMs: Long,
        alternativeTag: JourneyAlternativeTag,
        isUlgowyTicket: Boolean = false
    ): TransitJourney? {
        val legsReversed = ArrayList<JourneyLeg>(round * 2 + 2)
        val egress = query.targetEgressMap[targetStopIdx]
        if (egress != null && egress.walkSeconds > 0 && !query.targetName.isNullOrBlank()) {
            val alightTime = tau[round][targetStopIdx]
            legsReversed.add(
                JourneyLeg.WalkLeg(
                    fromName = data.stopNames[targetStopIdx],
                    toStopId = "address_target",
                    toName = query.targetName,
                    fromLat = data.stopLats[targetStopIdx],
                    fromLon = data.stopLons[targetStopIdx],
                    toLat = query.targetLat,
                    toLon = query.targetLon,
                    distanceMeters = egress.distanceMeters,
                    startTimeSec = alightTime,
                    endTimeSec = alightTime + egress.walkSeconds
                )
            )
        }
        var curRound = round
        var curStop = targetStopIdx

        while (curRound >= 0 && curStop >= 0) {
            when (parentType[curRound][curStop]) {
                PARENT_FOOTPATH -> {
                    val prevStop = parentPrevStop[curRound][curStop]
                    if (prevStop < 0) break
                    val arrTime = tau[curRound][curStop]
                    val dist = parentWalkDist[curRound][curStop]
                    val walkSec = GeoUtils.walkingTimeSeconds(dist.toDouble())
                    legsReversed.add(
                        JourneyLeg.WalkLeg(
                            fromName = data.stopNames[prevStop],
                            toStopId = data.stopIds[curStop],
                            toName = data.stopNames[curStop],
                            fromLat = data.stopLats[prevStop],
                            fromLon = data.stopLons[prevStop],
                            toLat = data.stopLats[curStop],
                            toLon = data.stopLons[curStop],
                            distanceMeters = dist,
                            startTimeSec = (arrTime - walkSec).coerceAtLeast(departureTimeSec),
                            endTimeSec = arrTime
                        )
                    )
                    curStop = prevStop
                }

                PARENT_TRANSIT -> {
                    val tripIdx = parentTrip[curRound][curStop]
                    val boardPos = parentBoardPos[curRound][curStop]
                    val alightPos = parentAlightPos[curRound][curStop]
                    val prevStop = parentPrevStop[curRound][curStop]
                    if (tripIdx < 0 || boardPos < 0 || alightPos <= boardPos) break

                    val patternIdx = data.tripPatternIndex[tripIdx]
                    val tripId = data.tripIds[tripIdx]
                    val delaySec = tripDelays[tripId] ?: 0

                    val stops = ArrayList<LegStop>(alightPos - boardPos + 1)
                    for (pos in boardPos..alightPos) {
                        val sIdx = data.patternStopAt(patternIdx, pos)
                        val sched = if (pos == boardPos) {
                            data.tripDepartureAt(tripIdx, pos)
                        } else {
                            data.tripArrivalAt(tripIdx, pos)
                        }
                        stops.add(
                            LegStop(
                                stopId = data.stopIds[sIdx],
                                stopName = data.stopNames[sIdx],
                                platformCode = data.stopPlatforms[sIdx],
                                zoneId = data.stopZones[sIdx],
                                latitude = data.stopLats[sIdx],
                                longitude = data.stopLons[sIdx],
                                scheduledTimeSec = sched,
                                effectiveTimeSec = sched + delaySec
                            )
                        )
                    }

                    legsReversed.add(
                        JourneyLeg.TransitLeg(
                            tripId = tripId,
                            routeShortName = data.patternRouteShortName[patternIdx],
                            routeType = data.patternRouteType[patternIdx],
                            routeColorHex = data.patternRouteColor[patternIdx],
                            headsign = data.patternHeadsign[patternIdx],
                            shapeId = data.tripShapeIds[tripIdx],
                            delaySeconds = delaySec,
                            stops = stops,
                            startTimeSec = stops.first().effectiveTimeSec,
                            endTimeSec = stops.last().effectiveTimeSec,
                            wheelchairAccessible = data.tripWheelchairAccessible[tripIdx]
                        )
                    )

                    curStop = prevStop
                    curRound -= 1
                }

                PARENT_ORIGIN -> {
                    val access = originAccessMap[curStop]
                    if (access != null && access.walkSeconds > 0) {
                        legsReversed.add(
                            JourneyLeg.WalkLeg(
                                fromName = query.originName,
                                toStopId = data.stopIds[curStop],
                                toName = data.stopNames[curStop],
                                fromLat = query.originLat,
                                fromLon = query.originLon,
                                toLat = data.stopLats[curStop],
                                toLon = data.stopLons[curStop],
                                distanceMeters = access.distanceMeters,
                                startTimeSec = departureTimeSec,
                                endTimeSec = departureTimeSec + access.walkSeconds
                            )
                        )
                    }
                    break
                }

                else -> break
            }
        }

        if (legsReversed.isEmpty()) return null
        val legs = legsReversed.reversed()
        val transitLegs = legs.filterIsInstance<JourneyLeg.TransitLeg>()
        if (transitLegs.isEmpty()) return null

        val ticket = ZtmTicketCalculator.calculateForLegs(legs, isUlgowy = isUlgowyTicket)

        val firstWalk = legs.firstOrNull() as? JourneyLeg.WalkLeg
        val firstTransit = transitLegs.first()
        val catchability = if (firstWalk != null) {
            val secondsUntilDep = (firstTransit.startTimeSec - departureTimeSec).coerceAtLeast(0)
            val margin = secondsUntilDep - firstWalk.durationSec
            val status = when {
                margin >= 120 -> CatchabilityStatus.COMFORTABLE
                margin >= 0 -> CatchabilityStatus.HURRY
                else -> CatchabilityStatus.TOO_TIGHT
            }
            CatchabilityInfo(
                walkDistanceMeters = firstWalk.distanceMeters,
                walkTimeSeconds = firstWalk.durationSec,
                secondsUntilDeparture = secondsUntilDep,
                marginSeconds = margin,
                status = status
            )
        } else {
            null
        }

        val journeyStart = legs.first().startTimeSec
        val journeyEnd = legs.last().endTimeSec
        val id = buildString {
            append(journeyStart).append('_').append(journeyEnd)
            transitLegs.forEach { append('_').append(it.tripId) }
        }

        return TransitJourney(
            id = id,
            legs = legs,
            departureTimeSec = journeyStart,
            arrivalTimeSec = journeyEnd,
            transferCount = (transitLegs.size - 1).coerceAtLeast(0),
            ticketRecommendation = ticket,
            catchability = catchability,
            searchDurationMs = searchDurationMs,
            alternativeTag = alternativeTag
        )
    }

    private fun TransitJourney.tripSignature(): String =
        transitLegs.joinToString("|") { it.tripId }

    companion object {
        private const val INF = Int.MAX_VALUE / 4
        private const val PARENT_NONE = 0
        private const val PARENT_ORIGIN = 1
        private const val PARENT_TRANSIT = 2
        private const val PARENT_FOOTPATH = 3
        private const val MIN_SAME_STOP_TRANSFER_SEC = 60
        private const val MAX_EXPECTED_DELAY_SEC = 20 * 60
        private const val MAX_EXPECTED_EARLY_SEC = 3 * 60

        private const val UPCOMING_SEARCH_HORIZON_SEC = 8 * 3600
        private const val EMPTY_STEP_SEC = 15 * 60
        private const val MAX_TIMELINE_ITERATIONS = 32

        private const val EARLIER_SEARCH_HORIZON_SEC = 3 * 3600
        private const val EARLIER_STEP_SEC = 10 * 60
        private const val MAX_EARLIER_STEPS = 18

        private const val MIN_DIST_FOR_DETOUR_CHECK_METERS = 800.0
        private const val MAX_DETOUR_DISTANCE_RATIO = 2.50
        private const val MAX_MODAL_DETOUR_RATIO = 2.25
        private const val MAX_DETOUR_DURATION_RATIO = 1.65
        private const val MAX_DETOUR_EXTRA_SEC = 10 * 60
    }
}
