package pl.poznan.offdojade.domain.routing

import pl.poznan.offdojade.data.local.entity.RouteEntity
import pl.poznan.offdojade.data.local.entity.StopEntity
import pl.poznan.offdojade.data.local.entity.StopTimeEntity
import pl.poznan.offdojade.data.local.entity.TransferEntity
import pl.poznan.offdojade.data.local.entity.TripEntity

/**
 * Строит компактный In-Memory [RaptorData] на базе примитивных массивов (`IntArray`)
 * из данных Room для одного календарного дня.
 */
object TransitGraphBuilder {

    private data class PatternKey(
        val routeId: String,
        val stopIndices: List<Int>
    )

    private data class TripWithTimes(
        val trip: TripEntity,
        val stopTimes: List<StopTimeEntity>
    )

    fun build(
        serviceDateYyyymmdd: Int,
        stops: List<StopEntity>,
        routes: List<RouteEntity>,
        trips: List<TripEntity>,
        stopTimes: List<StopTimeEntity>,
        transfers: List<TransferEntity>
    ): RaptorData {
        val stopCount = stops.size
        val stopIds = Array(stopCount) { stops[it].stopId }
        val stopNames = Array(stopCount) { stops[it].stopName }
        val stopLats = DoubleArray(stopCount) { stops[it].stopLat }
        val stopLons = DoubleArray(stopCount) { stops[it].stopLon }
        val stopZones = Array(stopCount) { stops[it].zoneId.ifBlank { "A" } }
        val stopPlatforms = Array(stopCount) { stops[it].platformCode }
        val stopIdToIndex = HashMap<String, Int>(stopCount * 2)
        for (i in 0 until stopCount) {
            stopIdToIndex[stopIds[i]] = i
        }

        val routeById = routes.associateBy { it.routeId }
        val tripById = trips.associateBy { it.tripId }

        // Группируем stop_times по trip_id (они уже отсортированы по trip_id, stop_sequence)
        val stopTimesByTrip = HashMap<String, MutableList<StopTimeEntity>>(trips.size * 2)
        for (st in stopTimes) {
            if (st.tripId !in tripById || st.stopId !in stopIdToIndex) continue
            stopTimesByTrip.getOrPut(st.tripId) { ArrayList(16) }.add(st)
        }

        // Группируем рейсы в паттерны с идентичной последовательностью остановок
        val patternMap = LinkedHashMap<PatternKey, MutableList<TripWithTimes>>()
        for ((tripId, stList) in stopTimesByTrip) {
            if (stList.size < 2) continue
            stList.sortBy { it.stopSequence }
            val trip = tripById[tripId] ?: continue
            val stopSeq = ArrayList<Int>(stList.size)
            for (st in stList) {
                val idx = stopIdToIndex[st.stopId] ?: continue
                stopSeq.add(idx)
            }
            if (stopSeq.size < 2) continue
            val key = PatternKey(trip.routeId, stopSeq)
            patternMap.getOrPut(key) { ArrayList() }.add(TripWithTimes(trip, stList))
        }

        val patternCount = patternMap.size
        val patternStopOffsets = IntArray(patternCount + 1)
        val patternTripOffsets = IntArray(patternCount + 1)
        val patternRouteShortName = Array(patternCount) { "" }
        val patternRouteType = IntArray(patternCount)
        val patternRouteColor = arrayOfNulls<String>(patternCount)
        val patternHeadsign = Array(patternCount) { "" }

        var totalPatternStops = 0
        var totalTrips = 0
        var totalStopTimeEntries = 0

        val entries = patternMap.entries.toList()
        for (p in entries.indices) {
            val (key, pTrips) = entries[p]
            // Сортируем рейсы внутри паттерна по времени отправления с первой остановки
            pTrips.sortBy { it.stopTimes.first().departureTimeSec }

            patternStopOffsets[p] = totalPatternStops
            patternTripOffsets[p] = totalTrips
            totalPatternStops += key.stopIndices.size
            totalTrips += pTrips.size
            totalStopTimeEntries += pTrips.size * key.stopIndices.size

            val route = routeById[key.routeId]
            patternRouteShortName[p] = route?.shortName ?: key.routeId
            patternRouteType[p] = route?.routeType ?: 3
            patternRouteColor[p] = route?.routeColor
            patternHeadsign[p] = pTrips.firstOrNull()?.trip?.tripHeadsign
                ?.ifBlank { route?.longName.orEmpty() }
                .orEmpty()
        }
        patternStopOffsets[patternCount] = totalPatternStops
        patternTripOffsets[patternCount] = totalTrips

        val patternStops = IntArray(totalPatternStops)
        val tripIds = Array(totalTrips) { "" }
        val tripShapeIds = Array(totalTrips) { "" }
        val tripPatternIndex = IntArray(totalTrips)
        val tripStopTimeOffsets = IntArray(totalTrips + 1)
        val arrivalTimesSec = IntArray(totalStopTimeEntries)
        val departureTimesSec = IntArray(totalStopTimeEntries)

        // Для обратного индекса stop -> (pattern, pos)
        val stopPatternCounts = IntArray(stopCount)

        var psCursor = 0
        var tripCursor = 0
        var stCursor = 0

        for (p in entries.indices) {
            val (key, pTrips) = entries[p]
            for (sIdx in key.stopIndices) {
                patternStops[psCursor++] = sIdx
                stopPatternCounts[sIdx]++
            }
            for (twt in pTrips) {
                tripIds[tripCursor] = twt.trip.tripId
                tripShapeIds[tripCursor] = twt.trip.shapeId
                tripPatternIndex[tripCursor] = p
                tripStopTimeOffsets[tripCursor] = stCursor
                for (st in twt.stopTimes) {
                    arrivalTimesSec[stCursor] = st.arrivalTimeSec
                    departureTimesSec[stCursor] = st.departureTimeSec
                    stCursor++
                }
                tripCursor++
            }
        }
        tripStopTimeOffsets[totalTrips] = stCursor

        // Строим CSR-массивы обратного индекса stop -> patterns
        val stopToPatternOffsets = IntArray(stopCount + 1)
        var totalStopPatterns = 0
        for (s in 0 until stopCount) {
            stopToPatternOffsets[s] = totalStopPatterns
            totalStopPatterns += stopPatternCounts[s]
        }
        stopToPatternOffsets[stopCount] = totalStopPatterns

        val stopToPatterns = IntArray(totalStopPatterns)
        val stopToPatternPos = IntArray(totalStopPatterns)
        val stopFillCursor = stopToPatternOffsets.copyOf()

        for (p in entries.indices) {
            val pStart = patternStopOffsets[p]
            val pEnd = patternStopOffsets[p + 1]
            for (pos in 0 until (pEnd - pStart)) {
                val sIdx = patternStops[pStart + pos]
                val writeIdx = stopFillCursor[sIdx]++
                stopToPatterns[writeIdx] = p
                stopToPatternPos[writeIdx] = pos
            }
        }

        // Строим CSR-массивы пеших пересадок (Footpaths)
        val fpByStop = Array<MutableList<TransferEntity>?>(stopCount) { null }
        var totalFootpaths = 0
        for (tr in transfers) {
            val fromIdx = stopIdToIndex[tr.fromStopId] ?: continue
            val toIdx = stopIdToIndex[tr.toStopId] ?: continue
            if (fromIdx == toIdx) continue
            val list = fpByStop[fromIdx] ?: ArrayList<TransferEntity>(4).also { fpByStop[fromIdx] = it }
            list.add(tr)
            totalFootpaths++
        }

        val footpathOffsets = IntArray(stopCount + 1)
        val footpathToStop = IntArray(totalFootpaths)
        val footpathWalkSec = IntArray(totalFootpaths)
        val footpathDistanceMeters = IntArray(totalFootpaths)
        var fpCursor = 0
        for (s in 0 until stopCount) {
            footpathOffsets[s] = fpCursor
            val list = fpByStop[s]
            if (list != null) {
                for (tr in list) {
                    val toIdx = stopIdToIndex[tr.toStopId] ?: continue
                    footpathToStop[fpCursor] = toIdx
                    footpathWalkSec[fpCursor] = tr.minTransferTimeSec.coerceAtLeast(30)
                    footpathDistanceMeters[fpCursor] = tr.distanceMeters.coerceAtLeast(10)
                    fpCursor++
                }
            }
        }
        footpathOffsets[stopCount] = fpCursor

        return RaptorData(
            serviceDateYyyymmdd = serviceDateYyyymmdd,
            stopCount = stopCount,
            stopIds = stopIds,
            stopNames = stopNames,
            stopLats = stopLats,
            stopLons = stopLons,
            stopZones = stopZones,
            stopPlatforms = stopPlatforms,
            stopIdToIndex = stopIdToIndex,
            patternCount = patternCount,
            patternStopOffsets = patternStopOffsets,
            patternStops = patternStops,
            patternTripOffsets = patternTripOffsets,
            patternRouteShortName = patternRouteShortName,
            patternRouteType = patternRouteType,
            patternRouteColor = patternRouteColor,
            patternHeadsign = patternHeadsign,
            tripCount = totalTrips,
            tripIds = tripIds,
            tripShapeIds = tripShapeIds,
            tripPatternIndex = tripPatternIndex,
            tripStopTimeOffsets = tripStopTimeOffsets,
            arrivalTimesSec = arrivalTimesSec,
            departureTimesSec = departureTimesSec,
            stopToPatternOffsets = stopToPatternOffsets,
            stopToPatterns = stopToPatterns,
            stopToPatternPos = stopToPatternPos,
            footpathOffsets = footpathOffsets,
            footpathToStop = footpathToStop,
            footpathWalkSec = footpathWalkSec,
            footpathDistanceMeters = footpathDistanceMeters
        )
    }
}
