package pl.poznan.offdojade.domain.routing

import pl.poznan.offdojade.data.gtfs.GeoUtils

data class SlicedRouteShape(
    /** Основной участок маршрута: от остановки посадки до остановки высадки (100% непрозрачность) */
    val activeCoordinates: List<Pair<Double, Double>>,
    /** Полупрозрачная трасса вне нужного маршрута: максимум до 1 ближайшей остановки до посадки и 1 после высадки */
    val outsideSegments: List<List<Pair<Double, Double>>>
)

/**
 * Разделяет полную геометрию линии (`shapes.txt`) на:
 * - активный участок между посадкой и высадкой;
 * - полупрозрачные «хвосты» трассы вне маршрута, строго обрезанные до ближайшей соседней остановки
 *   (максимум 1 остановка до посадки и 1 остановка после высадки).
 */
object ShapeSlicer {

    fun slice(
        shapePoints: List<Pair<Double, Double>>,
        legStops: List<Pair<Double, Double>>,
        prevStop: Pair<Double, Double>?,
        nextStop: Pair<Double, Double>?
    ): SlicedRouteShape {
        if (legStops.size < 2) {
            return SlicedRouteShape(activeCoordinates = legStops, outsideSegments = emptyList())
        }

        val boarding = legStops.first()
        val alighting = legStops.last()

        if (shapePoints.size < 2) {
            val outside = buildList {
                if (prevStop != null) add(listOf(prevStop, boarding))
                if (nextStop != null) add(listOf(alighting, nextStop))
            }
            return SlicedRouteShape(activeCoordinates = legStops, outsideSegments = outside)
        }

        val boardIdx = findClosestIndex(shapePoints, boarding, 0, shapePoints.lastIndex)
        val alightIdx = findClosestIndex(shapePoints, alighting, boardIdx, shapePoints.lastIndex)

        val active = if (alightIdx > boardIdx) {
            shapePoints.subList(boardIdx, alightIdx + 1)
        } else {
            legStops
        }

        val outside = ArrayList<List<Pair<Double, Double>>>(2)

        if (prevStop != null && boardIdx > 0) {
            val prevIdx = findClosestIndex(shapePoints, prevStop, 0, boardIdx)
            if (prevIdx < boardIdx) {
                outside.add(shapePoints.subList(prevIdx, boardIdx + 1))
            }
        }

        if (nextStop != null && alightIdx < shapePoints.lastIndex) {
            val nextIdx = findClosestIndex(shapePoints, nextStop, alightIdx, shapePoints.lastIndex)
            if (nextIdx > alightIdx) {
                outside.add(shapePoints.subList(alightIdx, nextIdx + 1))
            }
        }

        return SlicedRouteShape(
            activeCoordinates = active,
            outsideSegments = outside
        )
    }

    private fun findClosestIndex(
        points: List<Pair<Double, Double>>,
        target: Pair<Double, Double>,
        startIdx: Int,
        endIdx: Int
    ): Int {
        var bestIdx = startIdx
        var bestDist = Double.MAX_VALUE
        for (i in startIdx..endIdx) {
            val (lat, lon) = points[i]
            val d = GeoUtils.haversineMeters(lat, lon, target.first, target.second)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }
}
