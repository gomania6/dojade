package pl.poznan.offdojade.domain.routing

import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.gtfs.GeoUtils
import pl.poznan.offdojade.domain.model.CatchabilityInfo
import pl.poznan.offdojade.domain.model.CatchabilityStatus
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.LegStop
import pl.poznan.offdojade.domain.model.NearbyStopBoard
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.domain.model.UpcomingDeparture
import pl.poznan.offdojade.domain.tariff.ZtmTicketCalculator
import kotlin.math.roundToInt

/**
 * Сверхбыстрый движок маршрутизации **RAPTOR (Round-Based Public Transit Routing)**.
 * Работает исключительно с примитивными массивами [RaptorData] в памяти (< 50 мс на поиск)
 * и учитывает живые задержки GTFS-RT (`effective_time = scheduled_time + delay_seconds`).
 */
class RaptorEngine {

    private data class OriginAccess(
        val stopIdx: Int,
        val walkSeconds: Int,
        val distanceMeters: Int
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
        maxOriginWalkMeters: Double = AppConfig.ORIGIN_WALK_MAX_DISTANCE_METERS
    ): List<TransitJourney> {
        val targetIndices = data.findSiblingStopIndices(targetStopId)
        if (targetIndices.isEmpty()) return emptyList()
        val accesses = ArrayList<OriginAccess>(16)

        for (i in 0 until data.stopCount) {
            val dist = GeoUtils.haversineMeters(
                originLat, originLon,
                data.stopLats[i], data.stopLons[i]
            )
            if (dist <= maxOriginWalkMeters) {
                accesses.add(
                    OriginAccess(
                        stopIdx = i,
                        walkSeconds = GeoUtils.walkingTimeSeconds(dist),
                        distanceMeters = dist.roundToInt()
                    )
                )
            }
        }

        // Если в радиусе 600 м нет остановок, берем до 3 ближайших, чтобы пользователь не остался без маршрута
        if (accesses.isEmpty() && data.stopCount > 0) {
            val nearest = (0 until data.stopCount)
                .map { i ->
                    val dist = GeoUtils.haversineMeters(originLat, originLon, data.stopLats[i], data.stopLons[i])
                    OriginAccess(i, GeoUtils.walkingTimeSeconds(dist), dist.roundToInt())
                }
                .sortedBy { it.distanceMeters }
                .take(3)
            accesses.addAll(nearest)
        }

        return executeRaptor(
            data = data,
            originAccesses = accesses,
            originLat = originLat,
            originLon = originLon,
            targetStopIndices = targetIndices,
            departureTimeSec = departureTimeSec,
            tripDelays = tripDelays,
            maxTransfers = maxTransfers
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
        maxTransfers: Int = AppConfig.RAPTOR_MAX_TRANSFERS
    ): List<TransitJourney> {
        val originIndices = data.findSiblingStopIndices(originStopId)
        val targetIndices = data.findSiblingStopIndices(targetStopId)
        if (originIndices.isEmpty() || targetIndices.isEmpty()) return emptyList()

        // Если Пункт А и Пункт Б — одна и та же объединённая остановка
        val targetSet = targetIndices.toHashSet()
        if (originIndices.any { it in targetSet }) return emptyList()

        val accesses = originIndices.map { idx ->
            OriginAccess(stopIdx = idx, walkSeconds = 0, distanceMeters = 0)
        }
        val firstOrigin = originIndices[0]

        return executeRaptor(
            data = data,
            originAccesses = accesses,
            originLat = data.stopLats[firstOrigin],
            originLon = data.stopLons[firstOrigin],
            targetStopIndices = targetIndices,
            departureTimeSec = departureTimeSec,
            tripDelays = tripDelays,
            maxTransfers = maxTransfers
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

        // Считаем минимальное расстояние до каждой объединённой остановки
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
            // Пропускаем конечную остановку, где посадки уже нет
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
        originAccesses: List<OriginAccess>,
        originLat: Double,
        originLon: Double,
        targetStopIndices: IntArray,
        departureTimeSec: Int,
        tripDelays: Map<String, Int>,
        maxTransfers: Int
    ): List<TransitJourney> {
        if (originAccesses.isEmpty() || targetStopIndices.isEmpty() || data.stopCount == 0) return emptyList()

        val startNano = System.nanoTime()
        val rounds = maxTransfers + 1 // раунд k = поездка с k-1 пересадками (k=1..maxTransfers+1)
        val stopCount = data.stopCount
        val patternCount = data.patternCount

        val isTargetStop = BooleanArray(stopCount)
        for (tIdx in targetStopIndices) {
            if (tIdx in 0 until stopCount) isTargetStop[tIdx] = true
        }

        // tau[k][stop] = лучшее время прибытия на stop за ровно k транзитных сегментов
        val tau = Array(rounds + 1) { IntArray(stopCount) { INF } }
        val bestArrival = IntArray(stopCount) { INF }

        // Массивы восстановления пути для каждого раунда k и остановки s
        val parentType = Array(rounds + 1) { IntArray(stopCount) { PARENT_NONE } }
        val parentPrevStop = Array(rounds + 1) { IntArray(stopCount) { -1 } }
        val parentTrip = Array(rounds + 1) { IntArray(stopCount) { -1 } }
        val parentBoardPos = Array(rounds + 1) { IntArray(stopCount) { -1 } }
        val parentAlightPos = Array(rounds + 1) { IntArray(stopCount) { -1 } }
        val parentWalkDist = Array(rounds + 1) { IntArray(stopCount) { 0 } }

        var markedStops = BooleanArray(stopCount)
        val originAccessMap = HashMap<Int, OriginAccess>(originAccesses.size * 2)

        // Раунд 0: пеший подход от начальной точки (или старт сразу со всех платформ Пункта А)
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

        // Распространение стартовых пеших переходов от Пункта А (если старт с конкретной остановки)
        if (originAccesses.all { it.walkSeconds == 0 }) {
            for (access in originAccesses) {
                val startStop = access.stopIdx
                val fpStart = data.footpathOffsets[startStop]
                val fpEnd = data.footpathOffsets[startStop + 1]
                for (f in fpStart until fpEnd) {
                    val toStop = data.footpathToStop[f]
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

        // Очередь паттернов для сканирования: earliestMarkedPos[pattern] = минимальная позиция помеченной остановки
        val patternEarliestPos = IntArray(patternCount) { INF }
        val activePatterns = IntArray(patternCount)
        var activePatternCount: Int

        for (k in 1..rounds) {
            // 1. Собираем паттерны, проходящие через помеченные на предыдущем раунде остановки
            activePatternCount = 0
            for (s in 0 until stopCount) {
                if (!markedStops[s]) continue
                val pStart = data.stopToPatternOffsets[s]
                val pEnd = data.stopToPatternOffsets[s + 1]
                for (idx in pStart until pEnd) {
                    val p = data.stopToPatterns[idx]
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

            // 2. Сканируем каждый активный паттерн
            for (apIdx in 0 until activePatternCount) {
                val p = activePatterns[apIdx]
                val startPos = patternEarliestPos[p]
                patternEarliestPos[p] = INF // сброс для следующего раунда

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

                    // 2a. Пытаемся выйти на текущей остановке из текущего рейса
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

                    // 2b. Проверяем, можем ли мы пересесть на более ранний рейс этого паттерна с остановки stop
                    val prevArrAtStop = tau[k - 1][stop]
                    if (prevArrAtStop != INF) {
                        val minBoardTime = prevArrAtStop + if (k > 1) MIN_SAME_STOP_TRANSFER_SEC else 0
                        val candidateTrip = findEarliestTrip(
                            data = data,
                            tripStart = tripStart,
                            tripEnd = tripEnd,
                            pos = pos,
                            minDepartureSec = minBoardTime,
                            tripDelays = tripDelays
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

            // 3. Пешие пересадки (Footpaths) от всех улучшенных остановок текущего раунда
            val footpathUpdated = BooleanArray(stopCount)
            for (s in 0 until stopCount) {
                if (!nextMarked[s]) continue
                val baseTime = tau[k][s]
                if (baseTime == INF) continue

                val fpStart = data.footpathOffsets[s]
                val fpEnd = data.footpathOffsets[s + 1]
                for (f in fpStart until fpEnd) {
                    val toStop = data.footpathToStop[f]
                    val walkArr = baseTime + data.footpathWalkSec[f]
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

        // Восстанавливаем Парето-оптимальные маршруты по каждому раунду k (выбирая лучшую платформу цели)
        val journeys = ArrayList<TransitJourney>(rounds)
        var bestSeenArrival = INF
        for (k in 1..rounds) {
            var bestTargetInRound = -1
            var bestArrInRound = INF
            for (tIdx in targetStopIndices) {
                val arr = tau[k][tIdx]
                if (arr < bestArrInRound) {
                    bestArrInRound = arr
                    bestTargetInRound = tIdx
                }
            }
            if (bestTargetInRound != -1 && bestArrInRound < bestSeenArrival) {
                bestSeenArrival = bestArrInRound
                val journey = reconstructJourney(
                    data = data,
                    round = k,
                    targetStopIdx = bestTargetInRound,
                    originLat = originLat,
                    originLon = originLon,
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
                    searchDurationMs = elapsedMs
                )
                if (journey != null) {
                    journeys.add(journey)
                }
            }
        }

        return journeys.sortedBy { it.arrivalTimeSec }
    }

    /**
     * Находит самый ранний рейс паттерна, у которого `effective_departure >= minDepartureSec`.
     * Рейсы отсортированы по плановому времени; учитываем возможные опоздания GTFS-RT.
     */
    private fun findEarliestTrip(
        data: RaptorData,
        tripStart: Int,
        tripEnd: Int,
        pos: Int,
        minDepartureSec: Int,
        tripDelays: Map<String, Int>
    ): Int {
        // Быстрый бинарный поиск нижней границы с запасом 20 минут на случай опоздавших рейсов
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
                // Дальнейшие рейсы заведомо позже уже найденного
                break
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
        round: Int,
        targetStopIdx: Int,
        originLat: Double,
        originLon: Double,
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
        searchDurationMs: Long
    ): TransitJourney? {
        val legsReversed = ArrayList<JourneyLeg>(round * 2 + 1)
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
                            endTimeSec = stops.last().effectiveTimeSec
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
                                fromName = "Моё местоположение",
                                toStopId = data.stopIds[curStop],
                                toName = data.stopNames[curStop],
                                fromLat = originLat,
                                fromLon = originLon,
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

        val ticket = ZtmTicketCalculator.calculateForLegs(legs)

        // Индикатор «Успею ли добежать» (сравниваем пеший подход к первой остановке и время до отправления)
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
            searchDurationMs = searchDurationMs
        )
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
    }
}
