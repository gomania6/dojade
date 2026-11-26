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
        val isEvening = isEveningOrNight(departureTimeSec)
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
            tripSettings = tripSettings,
            isEveningOrNight = isEvening
        ) ?: return emptyList()

        val effectiveMaxTransfers = if (isEvening && !tripSettings.avoidTransfers) {
            maxOf(tripSettings.effectiveMaxTransfers, 3)
        } else {
            tripSettings.effectiveMaxTransfers
        }
        val collectedByFirstTrip = LinkedHashMap<String, TransitJourney>()
        val seenTripSignatures = HashSet<String>()
        val maxWindowEnd = departureTimeSec + if (isEvening) EVENING_SEARCH_HORIZON_SEC else UPCOMING_SEARCH_HORIZON_SEC
        var cursorTime = departureTimeSec
        var iterations = 0
        val maxIterations = if (isEvening) EVENING_MAX_TIMELINE_ITERATIONS else MAX_TIMELINE_ITERATIONS

        while (collectedByFirstTrip.size < limit && cursorTime <= maxWindowEnd && iterations < maxIterations) {
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
                if (journey.transitDepartureTimeSec < minDepartureInBatch) {
                    minDepartureInBatch = journey.transitDepartureTimeSec
                }
                val tripSig = journey.tripSignature()
                if (journey.id !in excludeIds && tripSig !in seenTripSignatures) {
                    seenTripSignatures.add(tripSig)
                    val firstTripKey = journey.transitLegs.firstOrNull()?.tripId ?: journey.id
                    val existing = collectedByFirstTrip[firstTripKey]
                    if (existing == null || scoreJourney(journey, tripSettings) < scoreJourney(existing, tripSettings)) {
                        collectedByFirstTrip[firstTripKey] = journey
                    }
                }
            }

            val nextCursor = maxOf(cursorTime + 60, minDepartureInBatch + 60)
            cursorTime = nextCursor
        }

        val rawUpcoming = collectedByFirstTrip.values
            .sortedWith(
                compareBy<TransitJourney> { it.transitDepartureTimeSec }
                    .thenBy { scoreJourney(it, tripSettings) }
            )

        val pruned = pruneDominatedJourneys(rawUpcoming, tripSettings)
        // В вечернее и ночное время (21:00–05:00) оставляем все возможные варианты, чтобы человек точно смог уехать.
        // В дневное время показываем маршруты с 2+ пересадками только когда простых вариантов (< 3) мало.
        val simpleRoutes = pruned.filter { it.transferCount <= 1 }
        val filtered = if (!isEvening && simpleRoutes.size >= MIN_SIMPLE_ROUTES_TO_HIDE_TWO_TRANSFERS) {
            simpleRoutes
        } else {
            pruned
        }
        return filtered.take(limit)
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
     * Поиск маршрутов с прибытием НЕ ПОЗЖЕ [arriveByTimeSec] (режим «Прибытие к …»).
     * Возвращает до [limit] наиболее удобных рейсов (с самым поздним выездом, чтобы не выходить слишком рано),
     * отсортированных хронологически.
     */
    fun searchArrivingByJourneys(
        data: RaptorData,
        originStopId: String?,
        originLat: Double,
        originLon: Double,
        targetStopId: String?,
        arriveByTimeSec: Int,
        tripDelays: Map<String, Int> = emptyMap(),
        tripSettings: TripSettings = TripSettings(),
        limit: Int = 5,
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
        val minSearchTime = (arriveByTimeSec - EARLIER_SEARCH_HORIZON_SEC).coerceAtLeast(0)

        var step = 1
        while (step <= MAX_EARLIER_STEPS) {
            val probeTime = (arriveByTimeSec - step * EARLIER_STEP_SEC).coerceAtLeast(minSearchTime)
            val batch = searchDiverseAtTime(
                data = data,
                query = resolved,
                departureTimeSec = probeTime,
                tripDelays = tripDelays,
                maxTransfers = effectiveMaxTransfers,
                tripSettings = tripSettings
            )
            for (journey in batch) {
                if (journey.arrivalTimeSec <= arriveByTimeSec) {
                    val tripSig = journey.tripSignature()
                    if (tripSig !in seenTripSignatures) {
                        seenTripSignatures.add(tripSig)
                        collected[journey.id] = journey
                    }
                }
            }
            if (collected.size >= limit * 2 || probeTime <= minSearchTime) break
            step++
        }

        // Оставляем самые поздние по выезду рейсы, успевающие к arriveByTimeSec, и сортируем хронологически
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
        val isEvening = isEveningOrNight(departureTimeSec)
        val eveningFootpathOverride = if (isEvening) EVENING_MAX_TRANSFER_WALK_METERS else null

        // 1. Основной проход со всеми разрешёнными видами транспорта
        var primary = executeRaptor(
            data = data,
            query = query,
            departureTimeSec = departureTimeSec,
            tripDelays = tripDelays,
            maxTransfers = maxTransfers,
            tripSettings = tripSettings,
            alternativeTag = JourneyAlternativeTag.STANDARD,
            maxFootpathMetersOverride = eveningFootpathOverride
        )
        // Если в «Удобном» / «Лучшем» режиме со строгим лимитом пешей пересадки ничего не найдено —
        // мягко расширяем радиус пересадки до 300 м (или 450 м вечером), чтобы не оставить пользователя без маршрута
        if (primary.isEmpty() && eveningFootpathOverride == null && tripSettings.routePreference != RoutePreference.FASTEST) {
            primary = executeRaptor(
                data = data,
                query = query,
                departureTimeSec = departureTimeSec,
                tripDelays = tripDelays,
                maxTransfers = maxTransfers,
                tripSettings = tripSettings,
                alternativeTag = JourneyAlternativeTag.STANDARD,
                maxFootpathMetersOverride = MAX_TRANSFER_WALK_FASTEST_METERS
            )
        }
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

        // 3. Дедупликация по набору рейсов + отсеивание бессмысленных пересадок на ту же линию
        val uniqueByTrips = LinkedHashMap<String, TransitJourney>()
        for (j in candidates) {
            if (hasRedundantSameLineTransfer(j)) continue
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

        // 4. Фильтр против кругов через весь город (Anti-Detour; вечером/ночью мягче, чтобы доехать на чём угодно)
        val noDetours = filterOutDetours(
            journeys = uniqueList,
            directDistMeters = directDistMeters,
            isEveningOrNight = isEvening
        )

        // 5. Не показываем варианты с 2+ пересадками, если в этот момент уже есть маршрут с 0–1 пересадкой
        // (вечером/ночью или когда простых вариантов нет — сохраняем 2+ пересадки, чтобы человек смог уехать)
        val simpleOptions = noDetours.filter { it.transferCount <= 1 }
        val transferFiltered = if (simpleOptions.isNotEmpty() && !isEvening) {
            if (tripSettings.routePreference == RoutePreference.FASTEST) {
                val bestSimpleArrival = simpleOptions.minOf { it.arrivalTimeSec }
                noDetours.filter { j ->
                    j.transferCount <= 1 || j.arrivalTimeSec <= bestSimpleArrival - FASTEST_TWO_TRANSFER_MIN_SAVING_SEC
                }
            } else {
                simpleOptions
            }
        } else {
            noDetours
        }

        // 6. Для одного и того же стартового рейса (firstTransit.tripId) оставляем только лучший вариант
        // (чтобы на один автобус 702 в 20:36 не выводилось 3 дубля 702→5, 702→163, 702→5→168)
        val bestPerFirstTrip = transferFiltered
            .groupBy { it.transitLegs.firstOrNull()?.tripId ?: it.id }
            .values
            .map { group -> group.minBy { scoreJourney(it, tripSettings) } }

        // 7. Тегируем прямые маршруты и сортируем с учетом профиля поездки (Удобно / Лучшее / Быстрое)
        val tagged = bestPerFirstTrip.map { j ->
            if (j.alternativeTag == JourneyAlternativeTag.STANDARD && j.transferCount == 0 && bestPerFirstTrip.size > 1) {
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
        directDistMeters: Double,
        isEveningOrNight: Boolean = false
    ): List<TransitJourney> {
        if (journeys.size <= 1) return journeys

        val bestDurationSec = journeys.minOf { (it.arrivalTimeSec - it.departureTimeSec).coerceAtLeast(60) }
        val bestArrivalSec = journeys.minOf { it.arrivalTimeSec }
        val hasDirectOption = journeys.any { it.transferCount == 0 }
        val durationRatio = if (isEveningOrNight) EVENING_MAX_DETOUR_DURATION_RATIO else MAX_DETOUR_DURATION_RATIO
        val extraSec = if (isEveningOrNight) EVENING_MAX_DETOUR_EXTRA_SEC else MAX_DETOUR_EXTRA_SEC
        val distanceRatio = if (isEveningOrNight) EVENING_MAX_DETOUR_DISTANCE_RATIO else MAX_DETOUR_DISTANCE_RATIO

        val kept = journeys.filter { j ->
            val durationSec = (j.arrivalTimeSec - j.departureTimeSec).coerceAtLeast(60)
            val travelledDist = computeTravelledDistanceMeters(j)

            // Всегда оставляем абсолютно самый быстрый по прибытию вариант (если он сам не безумный)
            val isFastestArrival = j.arrivalTimeSec == bestArrivalSec
            if (isFastestArrival && (directDistMeters < 1000.0 || travelledDist <= directDistMeters * 3.5)) {
                return@filter true
            }

            // Проверка 1: длительность поездки не должна быть чрезмерно больше лучшего варианта
            val maxAllowedDuration = (bestDurationSec * durationRatio).toInt() + extraSec
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
                    else -> distanceRatio
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

    /**
     * Максимальная дистанция пешей пересадки между разными остановками в зависимости от режима:
     * - FASTEST (Быстрый): до 300 м
     * - BEST (Лучший): золотая середина — до 220 м
     * - CONVENIENT (Удобный): избегает смены остановок пешком — до 120 м
     */
    private fun maxTransferWalkMeters(preference: RoutePreference): Int = when (preference) {
        RoutePreference.FASTEST -> MAX_TRANSFER_WALK_FASTEST_METERS
        RoutePreference.BEST -> MAX_TRANSFER_WALK_BEST_METERS
        RoutePreference.CONVENIENT -> MAX_TRANSFER_WALK_CONVENIENT_METERS
    }

    /**
     * Многокритериальная оценка маршрута (как в Jakdojade / McRAPTOR):
     * - FASTEST: минимальное время прибытия, короткие пересадки до 300 м, без лишних пересадок
     * - CONVENIENT: минимум пересадок и минимум пеших переходов между разными остановками
     * - BEST: сбалансированный вариант между Быстрым и Удобным
     */
    private fun scoreJourney(journey: TransitJourney, settings: TripSettings): Int {
        val durationSec = (journey.arrivalTimeSec - journey.departureTimeSec).coerceAtLeast(0)
        val walkSec = journey.legs.filterIsInstance<JourneyLeg.WalkLeg>().sumOf { it.durationSec }
        val transferWaitSec = computeTransferWaitSeconds(journey)
        val crossStopTransferCount = countCrossStopWalkTransfers(journey)
        val tramLegCount = journey.transitLegs.count { it.isTram }
        val preferredMatches = journey.transitLegs.count { settings.isLinePreferred(it.routeShortName) }
        val preferredBonus = preferredMatches * 210
        val avoidTransfersPenalty = if (settings.avoidTransfers) journey.transferCount * 720 else 0

        return when (settings.routePreference) {
            RoutePreference.FASTEST ->
                journey.arrivalTimeSec +
                    journey.transferCount * 90 +
                    (walkSec * 0.25).toInt() +
                    avoidTransfersPenalty -
                    preferredBonus / 2

            RoutePreference.BEST ->
                durationSec +
                    journey.transferCount * 300 +
                    crossStopTransferCount * 120 +
                    (transferWaitSec * 0.55).toInt() +
                    (walkSec * 0.90).toInt() -
                    tramLegCount * 45 +
                    avoidTransfersPenalty -
                    preferredBonus

            RoutePreference.CONVENIENT ->
                durationSec +
                    journey.transferCount * 720 +
                    crossStopTransferCount * 360 +
                    (transferWaitSec * 0.95).toInt() +
                    (walkSec * 1.90).toInt() -
                    tramLegCount * 60 +
                    avoidTransfersPenalty -
                    preferredBonus
        }
    }

    /**
     * Подсчитывает число пересадок, требующих пешего перехода на другую остановку (> 15 м).
     */
    private fun countCrossStopWalkTransfers(journey: TransitJourney): Int {
        val legs = journey.legs
        var count = 0
        for (i in 1 until legs.lastIndex) {
            val leg = legs[i]
            if (leg is JourneyLeg.WalkLeg && leg.distanceMeters > 15 &&
                legs[i - 1] is JourneyLeg.TransitLeg && legs[i + 1] is JourneyLeg.TransitLeg
            ) {
                count++
            }
        }
        return count
    }

    /**
     * Суммарное время ожидания на пересадках между сегментами транспорта.
     */
    private fun computeTransferWaitSeconds(journey: TransitJourney): Int {
        val transit = journey.transitLegs
        if (transit.size < 2) return 0
        var totalWait = 0
        for (i in 0 until transit.lastIndex) {
            val gap = (transit[i + 1].startTimeSec - transit[i].endTimeSec).coerceAtLeast(0)
            totalWait += gap
        }
        return totalWait
    }

    /**
     * Проверяет, нет ли в маршруте бессмысленной пересадки с линии X на ту же линию X.
     */
    private fun hasRedundantSameLineTransfer(journey: TransitJourney): Boolean {
        val transit = journey.transitLegs
        for (i in 0 until transit.lastIndex) {
            if (transit[i].routeShortName.equals(transit[i + 1].routeShortName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * Парето-фильтрация: убирает строго доминируемые маршруты, которые выезжают раньше (или так же),
     * но приезжают позже (или так же) при большем или равном числе пересадок и сопоставимой пешей части.
     */
    private fun pruneDominatedJourneys(
        journeys: List<TransitJourney>,
        settings: TripSettings
    ): List<TransitJourney> {
        if (journeys.size <= 1) return journeys
        return journeys.filter { candidate ->
            val isDominated = journeys.any { other ->
                if (other.id == candidate.id) return@any false
                val leavesLaterOrSame = other.transitDepartureTimeSec >= candidate.transitDepartureTimeSec
                val arrivesEarlierOrSame = other.arrivalTimeSec <= candidate.arrivalTimeSec
                val strictlyBetterTime = other.transitDepartureTimeSec > candidate.transitDepartureTimeSec ||
                    other.arrivalTimeSec < candidate.arrivalTimeSec
                val notMoreTransfers = other.transferCount <= candidate.transferCount
                val comparableWalk = other.totalWalkMeters <= candidate.totalWalkMeters + 150
                val sameModeOrBetterScore = scoreJourney(other, settings) <= scoreJourney(candidate, settings)
                leavesLaterOrSame && arrivesEarlierOrSame && strictlyBetterTime &&
                    notMoreTransfers && comparableWalk && sameModeOrBetterScore
            }
            !isDominated
        }.ifEmpty { journeys }
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
        tripSettings: TripSettings,
        isEveningOrNight: Boolean = false
    ): ResolvedQuery? {
        val originAccesses: List<OriginAccess>
        val resolvedOriginLat: Double
        val resolvedOriginLon: Double
        val originNearbyMeters = if (isEveningOrNight) 650.0 else 500.0
        val targetEgressMeters = if (isEveningOrNight) 450.0 else 300.0

        if (!originStopId.isNullOrBlank()) {
            val originIndices = data.findSiblingStopIndices(originStopId)
            if (originIndices.isEmpty()) return null
            resolvedOriginLat = data.stopLats[originIndices[0]]
            resolvedOriginLon = data.stopLons[originIndices[0]]
            val ownSet = originIndices.toHashSet()
            val ownAccesses = originIndices.map { OriginAccess(it, 0, 0) }
            // Добавляем также ближайшие остановки в пешей доступности (до 500 м днём, до 650 м вечером/ночью)
            val nearbyAccesses = findNearbyAccesses(
                data = data,
                lat = resolvedOriginLat,
                lon = resolvedOriginLon,
                maxMeters = originNearbyMeters,
                tripSettings = tripSettings
            ).filter { it.stopIdx !in ownSet }
            originAccesses = ownAccesses + nearbyAccesses
        } else {
            originAccesses = findNearbyAccesses(
                data = data,
                lat = originLat,
                lon = originLon,
                maxMeters = if (isEveningOrNight) maxOf(AppConfig.ORIGIN_WALK_MAX_DISTANCE_METERS, 750.0) else AppConfig.ORIGIN_WALK_MAX_DISTANCE_METERS,
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
        val ownTargetSet: Set<Int>

        if (!targetStopId.isNullOrBlank()) {
            val ownTargets = data.findSiblingStopIndices(targetStopId)
            if (ownTargets.isEmpty()) return null
            ownTargetSet = ownTargets.toHashSet()
            resolvedTargetLat = data.stopLats[ownTargets[0]]
            resolvedTargetLon = data.stopLons[ownTargets[0]]
            // Позволяем также выйти на ближайшей соседней остановке (до 300 м днём, до 450 м вечером/ночью)
            val nearbyEgress = findNearbyAccesses(
                data = data,
                lat = resolvedTargetLat,
                lon = resolvedTargetLon,
                maxMeters = targetEgressMeters,
                tripSettings = tripSettings
            ).filter { it.stopIdx !in ownTargetSet }
            targetStopIndices = (ownTargets.toList() + nearbyEgress.map { it.stopIdx }).toIntArray()
            targetEgressMap = nearbyEgress.associateBy { it.stopIdx }
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
            ownTargetSet = targetStopIndices.toHashSet()
            resolvedTargetLat = targetLat
            resolvedTargetLon = targetLon
            targetEgressMap = egressAccesses.associateBy { it.stopIdx }
        } else {
            return null
        }

        // Проверяем совпадение только среди собственных платформ старта и финиша (чтобы короткие поездки < 500 м не отменялись!)
        val ownOriginSet = if (!originStopId.isNullOrBlank()) {
            data.findSiblingStopIndices(originStopId).toHashSet()
        } else {
            emptySet()
        }
        if (ownOriginSet.isNotEmpty() && !targetStopId.isNullOrBlank() && ownOriginSet.any { it in ownTargetSet }) {
            return null
        }

        // Исключаем целевые остановки из пешего стартового доступа, чтобы всегда строился транзитный маршрут
        val cleanedOriginAccesses = originAccesses.filter { it.stopIdx !in ownTargetSet }
        if (cleanedOriginAccesses.isEmpty()) return null

        val resolvedTargetDisplayName = targetName ?: targetStopId?.let { id ->
            data.stopIdToIndex[id]?.let { data.stopNames[it] }
        }

        return ResolvedQuery(
            originAccesses = cleanedOriginAccesses,
            originLat = resolvedOriginLat,
            originLon = resolvedOriginLon,
            originName = originName,
            targetStopIndices = targetStopIndices,
            targetLat = resolvedTargetLat,
            targetLon = resolvedTargetLon,
            targetName = resolvedTargetDisplayName,
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
        alternativeTag: JourneyAlternativeTag,
        maxFootpathMetersOverride: Int? = null
    ): List<TransitJourney> {
        val originAccesses = query.originAccesses
        val targetStopIndices = query.targetStopIndices
        if (originAccesses.isEmpty() || targetStopIndices.isEmpty() || data.stopCount == 0) return emptyList()

        val startNano = System.nanoTime()
        val rounds = maxTransfers + 1
        val stopCount = data.stopCount
        val patternCount = data.patternCount
        val minTransferSec = if (tripSettings.routePreference == RoutePreference.FASTEST) {
            maxOf(MIN_SAME_STOP_TRANSFER_SEC, minOf(tripSettings.minTransferSeconds, 90))
        } else {
            maxOf(MIN_SAME_STOP_TRANSFER_SEC, tripSettings.minTransferSeconds)
        }
        val maxFootpathMeters = maxFootpathMetersOverride ?: maxTransferWalkMeters(tripSettings.routePreference)

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
                if (bestArrival[tIdx] < INF) {
                    val totalTargetArr = bestArrival[tIdx] + (query.targetEgressMap[tIdx]?.walkSeconds ?: 0)
                    if (totalTargetArr < targetBest) targetBest = totalTargetArr
                }
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
                                val totalTargetArr = effectiveArr + (query.targetEgressMap[stop]?.walkSeconds ?: 0)
                                if (totalTargetArr < targetBest) {
                                    targetBest = totalTargetArr
                                }
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
                            val betterBoardOnSameTrip = candidateTrip == currentTrip && boardStop != -1 && (
                                (parentType[k - 1][boardStop] == PARENT_FOOTPATH && parentType[k - 1][stop] != PARENT_FOOTPATH) ||
                                    (parentWalkDist[k - 1][stop] < parentWalkDist[k - 1][boardStop])
                                )
                            if (currentTrip == -1 || candDep < curDep || betterBoardOnSameTrip) {
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
                    // Ограничиваем длину пешей пересадки по профилю (Быстрый ≤300м, Лучший ≤220м, Удобный ≤120м)
                    if (!isTargetStop[toStop] && data.footpathDistanceMeters[f] > maxFootpathMeters) continue
                    val transferTime = if (isTargetStop[toStop]) {
                        data.footpathWalkSec[f]
                    } else {
                        maxOf(data.footpathWalkSec[f], minTransferSec)
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
                            val totalTargetArr = walkArr + (query.targetEgressMap[toStop]?.walkSeconds ?: 0)
                            if (totalTargetArr < targetBest) {
                                targetBest = totalTargetArr
                            }
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
                    isUlgowyTicket = tripSettings.isUlgowyTicket,
                    minTransferSec = minTransferSec
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
        isUlgowyTicket: Boolean = false,
        minTransferSec: Int = MIN_SAME_STOP_TRANSFER_SEC
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
                                effectiveTimeSec = sched + delaySec,
                                isOnDemand = data.tripIsOnDemandAt(tripIdx, pos)
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
        val rawLegs = optimizeTransferAlighting(
            data = data,
            legs = legsReversed.reversed(),
            tripDelays = tripDelays,
            minTransferSec = minTransferSec
        )
        val transitLegs = rawLegs.filterIsInstance<JourneyLeg.TransitLeg>()
        if (transitLegs.isEmpty()) return null

        // Сдвигаем стартовый пеший участок вплотную к первому рейсу,
        // чтобы не выходить на 30–60 мин раньше и не искажать время отправления и длительность поездки
        val firstTransit = transitLegs.first()
        val legs = if (rawLegs.firstOrNull() is JourneyLeg.WalkLeg) {
            val firstWalk = rawLegs.first() as JourneyLeg.WalkLeg
            val walkDur = firstWalk.durationSec
            val idealStart = (firstTransit.startTimeSec - walkDur).coerceAtLeast(departureTimeSec)
            val shiftedWalk = firstWalk.copy(
                startTimeSec = idealStart,
                endTimeSec = idealStart + walkDur
            )
            listOf(shiftedWalk) + rawLegs.drop(1)
        } else {
            rawLegs
        }

        val ticket = ZtmTicketCalculator.calculateForLegs(legs, isUlgowy = isUlgowyTicket)

        val firstWalk = legs.firstOrNull() as? JourneyLeg.WalkLeg
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

    /**
     * Устраняет преждевременную высадку за 1–2 остановки до узла пересадки:
     * если тот же рейс едет дальше к остановке, которая ближе к следующей посадке,
     * и пассажир всё равно успевает на следующий рейс — продлеваем поездку до более близкой остановки.
     */
    private fun optimizeTransferAlighting(
        data: RaptorData,
        legs: List<JourneyLeg>,
        tripDelays: Map<String, Int>,
        minTransferSec: Int
    ): List<JourneyLeg> {
        if (legs.size < 3) return legs
        val result = ArrayList<JourneyLeg>(legs.size)
        var i = 0
        while (i < legs.size) {
            val current = legs[i]
            val walk = legs.getOrNull(i + 1) as? JourneyLeg.WalkLeg
            val nextTransit = legs.getOrNull(i + 2) as? JourneyLeg.TransitLeg

            if (current is JourneyLeg.TransitLeg && walk != null && nextTransit != null && walk.distanceMeters > 15) {
                val tripIdx = data.tripIds.indexOf(current.tripId)
                if (tripIdx >= 0) {
                    val patternIdx = data.tripPatternIndex[tripIdx]
                    val pStopCount = data.patternStopCount(patternIdx)
                    val curAlightStopId = current.alightingStop.stopId
                    var curAlightPos = -1
                    for (pos in 0 until pStopCount) {
                        val sIdx = data.patternStopAt(patternIdx, pos)
                        if (data.stopIds[sIdx] == curAlightStopId) {
                            curAlightPos = pos
                            break
                        }
                    }

                    if (curAlightPos in 0 until pStopCount - 1) {
                        val delaySec = tripDelays[current.tripId] ?: current.delaySeconds
                        var bestPos = -1
                        var bestDist = walk.distanceMeters
                        var bestArrEff = 0
                        var bestWalkSec = 0

                        for (pos in (curAlightPos + 1) until pStopCount) {
                            val sIdx = data.patternStopAt(patternIdx, pos)
                            val dist = GeoUtils.haversineMeters(
                                data.stopLats[sIdx], data.stopLons[sIdx],
                                nextTransit.boardingStop.latitude, nextTransit.boardingStop.longitude
                            ).roundToInt()
                            if (dist + 25 >= bestDist) continue

                            val arrEff = data.tripArrivalAt(tripIdx, pos) + delaySec
                            val walkSec = if (dist <= 15) 0 else GeoUtils.walkingTimeSeconds(dist.toDouble())
                            val neededTransfer = maxOf(walkSec, minTransferSec)
                            if (arrEff + neededTransfer <= nextTransit.startTimeSec) {
                                bestPos = pos
                                bestDist = dist
                                bestArrEff = arrEff
                                bestWalkSec = walkSec
                            }
                        }

                        if (bestPos > curAlightPos) {
                            val extraStops = ArrayList<LegStop>(bestPos - curAlightPos)
                            for (pos in (curAlightPos + 1)..bestPos) {
                                val sIdx = data.patternStopAt(patternIdx, pos)
                                val sched = data.tripArrivalAt(tripIdx, pos)
                                extraStops.add(
                                    LegStop(
                                        stopId = data.stopIds[sIdx],
                                        stopName = data.stopNames[sIdx],
                                        platformCode = data.stopPlatforms[sIdx],
                                        zoneId = data.stopZones[sIdx],
                                        latitude = data.stopLats[sIdx],
                                        longitude = data.stopLons[sIdx],
                                        scheduledTimeSec = sched,
                                        effectiveTimeSec = sched + delaySec,
                                        isOnDemand = data.tripIsOnDemandAt(tripIdx, pos)
                                    )
                                )
                            }
                            val extendedTransit = current.copy(
                                stops = current.stops + extraStops,
                                endTimeSec = bestArrEff
                            )
                            result.add(extendedTransit)
                            if (bestDist > 15) {
                                val newStop = extraStops.last()
                                result.add(
                                    walk.copy(
                                        fromName = newStop.stopName,
                                        fromLat = newStop.latitude,
                                        fromLon = newStop.longitude,
                                        distanceMeters = bestDist,
                                        startTimeSec = bestArrEff,
                                        endTimeSec = bestArrEff + bestWalkSec
                                    )
                                )
                            }
                            i += 2
                            continue
                        }
                    }
                }
            }

            result.add(current)
            i++
        }
        return result
    }

    private fun isEveningOrNight(timeSec: Int): Boolean {
        val mod = ((timeSec % 86400) + 86400) % 86400
        return mod >= 21 * 3600 || mod < 5 * 3600
    }

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
        private const val EVENING_SEARCH_HORIZON_SEC = 10 * 3600
        private const val EMPTY_STEP_SEC = 15 * 60
        private const val MAX_TIMELINE_ITERATIONS = 32
        private const val EVENING_MAX_TIMELINE_ITERATIONS = 42

        private const val EARLIER_SEARCH_HORIZON_SEC = 3 * 3600
        private const val EARLIER_STEP_SEC = 10 * 60
        private const val MAX_EARLIER_STEPS = 18

        private const val MIN_DIST_FOR_DETOUR_CHECK_METERS = 800.0
        private const val MAX_DETOUR_DISTANCE_RATIO = 2.50
        private const val EVENING_MAX_DETOUR_DISTANCE_RATIO = 3.50
        private const val MAX_MODAL_DETOUR_RATIO = 2.25
        private const val MAX_DETOUR_DURATION_RATIO = 1.65
        private const val EVENING_MAX_DETOUR_DURATION_RATIO = 2.20
        private const val MAX_DETOUR_EXTRA_SEC = 10 * 60
        private const val EVENING_MAX_DETOUR_EXTRA_SEC = 20 * 60

        private const val MAX_TRANSFER_WALK_FASTEST_METERS = 300
        private const val MAX_TRANSFER_WALK_BEST_METERS = 220
        private const val MAX_TRANSFER_WALK_CONVENIENT_METERS = 120
        private const val EVENING_MAX_TRANSFER_WALK_METERS = 450
        private const val MIN_SIMPLE_ROUTES_TO_HIDE_TWO_TRANSFERS = 3
        private const val FASTEST_TWO_TRANSFER_MIN_SAVING_SEC = 8 * 60
    }
}
