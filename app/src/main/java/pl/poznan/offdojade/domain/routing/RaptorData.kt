package pl.poznan.offdojade.domain.routing

/**
 * Компактная In-Memory структура расписания на один день на базе примитивных массивов (`IntArray`).
 * Обеспечивает работу алгоритма RAPTOR без единого SQL-запроса во время поиска (< 50 мс).
 */
class RaptorData(
    val serviceDateYyyymmdd: Int,

    // --- Остановки (индексы 0 until stopCount) ---
    val stopCount: Int,
    val stopIds: Array<String>,
    val stopNames: Array<String>,
    val stopLats: DoubleArray,
    val stopLons: DoubleArray,
    val stopZones: Array<String>,
    val stopPlatforms: Array<String>,
    val stopIdToIndex: Map<String, Int>,

    // --- Паттерны маршрутов (уникальная последовательность остановок для route_id) ---
    val patternCount: Int,
    /** Длина = patternCount + 1; остановки паттерна p лежат в patternStops[patternStopOffsets[p] until patternStopOffsets[p+1]] */
    val patternStopOffsets: IntArray,
    val patternStops: IntArray,
    /** Длина = patternCount + 1; рейсы паттерна p лежат в [patternTripOffsets[p] until patternTripOffsets[p+1]], отсортированы по времени отправления */
    val patternTripOffsets: IntArray,
    val patternRouteShortName: Array<String>,
    val patternRouteType: IntArray,
    val patternRouteColor: Array<String?>,
    val patternHeadsign: Array<String>,

    // --- Рейсы (индексы 0 until tripCount) ---
    val tripCount: Int,
    val tripIds: Array<String>,
    val tripShapeIds: Array<String>,
    val tripPatternIndex: IntArray,
    /** GTFS wheelchair_accessible: 0 = unknown, 1 = low-floor, 2 = not accessible */
    val tripWheelchairAccessible: IntArray = IntArray(tripCount),
    /** Длина = tripCount + 1; stop_times рейса t лежат в arrivalTimesSec/departureTimesSec[tripStopTimeOffsets[t] + pos] */
    val tripStopTimeOffsets: IntArray,
    val arrivalTimesSec: IntArray,
    val departureTimesSec: IntArray,
    /** 1 = остановка по требованию (pickup_type=3 или drop_off_type=3) */
    val stopTimeOnDemand: ByteArray = ByteArray(arrivalTimesSec.size),

    // --- Обратный индекс: остановка -> список паттернов, проходящих через неё ---
    /** Длина = stopCount + 1 */
    val stopToPatternOffsets: IntArray,
    val stopToPatterns: IntArray,
    /** Позиция остановки внутри соответствующего паттерна */
    val stopToPatternPos: IntArray,

    // --- Пешие пересадки (Footpaths) ---
    /** Длина = stopCount + 1 */
    val footpathOffsets: IntArray,
    val footpathToStop: IntArray,
    val footpathWalkSec: IntArray,
    val footpathDistanceMeters: IntArray
) {
    fun patternStopCount(patternIdx: Int): Int =
        patternStopOffsets[patternIdx + 1] - patternStopOffsets[patternIdx]

    fun patternStopAt(patternIdx: Int, pos: Int): Int =
        patternStops[patternStopOffsets[patternIdx] + pos]

    fun tripArrivalAt(tripIdx: Int, pos: Int): Int =
        arrivalTimesSec[tripStopTimeOffsets[tripIdx] + pos]

    fun tripDepartureAt(tripIdx: Int, pos: Int): Int =
        departureTimesSec[tripStopTimeOffsets[tripIdx] + pos]

    fun tripIsOnDemandAt(tripIdx: Int, pos: Int): Boolean {
        val idx = tripStopTimeOffsets[tripIdx] + pos
        if (idx in stopTimeOnDemand.indices && stopTimeOnDemand[idx] == 1.toByte()) {
            return true
        }
        val pIdx = tripPatternIndex[tripIdx]
        val sIdx = patternStopAt(pIdx, pos)
        val name = stopNames[sIdx]
        return isOnDemandByName(name)
    }

    private fun isOnDemandByName(name: String): Boolean {
        val lower = name.lowercase(java.util.Locale.ROOT)
        return lower.contains("n/ż") ||
            lower.contains("n/z") ||
            lower.contains("(nż)") ||
            lower.contains(" nż") ||
            lower.contains("na żądanie")
    }

    /**
     * Группировка всех платформ с одинаковым названием (например, все перроны "Rondo Kaponiera")
     * в один массив индексов для бесшовного поиска Пункт А -> Пункт Б.
     */
    val stopIndicesByNormalizedName: Map<String, IntArray> by lazy {
        val grouped = LinkedHashMap<String, MutableList<Int>>(stopCount / 2 + 1)
        for (i in 0 until stopCount) {
            val key = normalizeStopName(stopNames[i])
            grouped.getOrPut(key) { ArrayList(2) }.add(i)
        }
        grouped.mapValues { (_, list) -> list.toIntArray() }
    }

    /**
     * Идентификатор логической группы остановки (0..G-1) для каждого `stopIdx`:
     * все платформы одного узла (например, все перроны "Rondo Kaponiera") получают один и тот же ID.
     * Используется в RAPTOR за O(1), чтобы запретить абсурдную поездку между платформами одной остановки.
     */
    val stopGroupIds: IntArray by lazy {
        val ids = IntArray(stopCount)
        var groupId = 0
        for (indices in stopIndicesByNormalizedName.values) {
            for (idx in indices) {
                ids[idx] = groupId
            }
            groupId++
        }
        ids
    }

    /** Быстрый O(1)-индекс `tripId -> tripIdx` (вместо линейного сканирования `tripIds.indexOf`). */
    val tripIdToIndex: Map<String, Int> by lazy {
        HashMap<String, Int>(tripCount * 2).apply {
            for (i in 0 until tripCount) {
                put(tripIds[i], i)
            }
        }
    }

    /**
     * Возвращает все индексы платформ, имеющих то же название, что и [stopId].
     */
    fun findSiblingStopIndices(stopId: String): IntArray {
        val idx = stopIdToIndex[stopId] ?: return IntArray(0)
        val key = normalizeStopName(stopNames[idx])
        return stopIndicesByNormalizedName[key] ?: intArrayOf(idx)
    }

    companion object {
        fun normalizeStopName(name: String): String =
            name.trim().lowercase(java.util.Locale.ROOT)
    }
}
