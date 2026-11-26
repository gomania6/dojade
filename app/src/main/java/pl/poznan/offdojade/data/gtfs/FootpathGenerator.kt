package pl.poznan.offdojade.data.gtfs

import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.local.entity.StopEntity
import pl.poznan.offdojade.data.local.entity.TransferEntity
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Генерирует пешие пересадки (footpaths) между всеми парами остановок,
 * находящихся на расстоянии менее [maxDistanceMeters] (по умолчанию 300 м),
 * используя пространственную сетку (spatial grid) для быстрого поиска O(N).
 */
object FootpathGenerator {

    // ~0.0027 deg lat ≈ 300 m, ~0.0045 deg lon at 52.4°N (Poznań) ≈ 300 m
    private const val CELL_LAT_DEG = 0.003
    private const val CELL_LON_DEG = 0.005

    fun generateFootpaths(
        stops: List<StopEntity>,
        existingPairs: Set<Pair<String, String>> = emptySet(),
        maxDistanceMeters: Double = AppConfig.FOOTPATH_MAX_DISTANCE_METERS,
        walkingSpeedMps: Double = AppConfig.WALKING_SPEED_MPS
    ): List<TransferEntity> {
        if (stops.size < 2) return emptyList()

        val grid = HashMap<Long, MutableList<StopEntity>>(stops.size)
        for (stop in stops) {
            val key = cellKey(latCell(stop.stopLat), lonCell(stop.stopLon))
            grid.getOrPut(key) { ArrayList(4) }.add(stop)
        }

        val generated = ArrayList<TransferEntity>(stops.size * 4)

        for (from in stops) {
            val baseLatCell = latCell(from.stopLat)
            val baseLonCell = lonCell(from.stopLon)

            for (dLat in -1..1) {
                for (dLon in -1..1) {
                    val neighbors = grid[cellKey(baseLatCell + dLat, baseLonCell + dLon)] ?: continue
                    for (to in neighbors) {
                        if (from.stopId == to.stopId) continue
                        if (existingPairs.contains(from.stopId to to.stopId)) continue

                        val distance = GeoUtils.haversineMeters(
                            from.stopLat, from.stopLon,
                            to.stopLat, to.stopLon
                        )
                        if (distance <= maxDistanceMeters) {
                            val walkSec = GeoUtils.walkingTimeSeconds(distance, walkingSpeedMps)
                            generated.add(
                                TransferEntity(
                                    fromStopId = from.stopId,
                                    toStopId = to.stopId,
                                    transferType = 2,
                                    minTransferTimeSec = walkSec,
                                    distanceMeters = distance.roundToInt()
                                )
                            )
                        }
                    }
                }
            }
        }

        return generated
    }

    private fun latCell(lat: Double): Int = floor(lat / CELL_LAT_DEG).toInt()
    private fun lonCell(lon: Double): Int = floor(lon / CELL_LON_DEG).toInt()

    private fun cellKey(latCell: Int, lonCell: Int): Long =
        (latCell.toLong() shl 32) or (lonCell.toLong() and 0xFFFF_FFFFL)
}
