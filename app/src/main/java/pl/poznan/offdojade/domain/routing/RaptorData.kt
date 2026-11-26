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
    /** Длина = tripCount + 1; stop_times рейса t лежат в arrivalTimesSec/departureTimesSec[tripStopTimeOffsets[t] + pos] */
    val tripStopTimeOffsets: IntArray,
    val arrivalTimesSec: IntArray,
    val departureTimesSec: IntArray,

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
