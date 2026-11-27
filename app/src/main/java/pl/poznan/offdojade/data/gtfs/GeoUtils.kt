package pl.poznan.offdojade.data.gtfs

import pl.poznan.offdojade.config.AppConfig
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoUtils {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Расстояние между двумя GPS-точками в метрах по формуле Хаверсина.
     */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val radLat1 = Math.toRadians(lat1)
        val radLat2 = Math.toRadians(lat2)

        val sinDLat = sin(dLat / 2.0)
        val sinDLon = sin(dLon / 2.0)
        val a = (sinDLat * sinDLat + cos(radLat1) * cos(radLat2) * sinDLon * sinDLon).coerceIn(0.0, 1.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Время пешего перехода в секундах при скорости [speedMps] (по умолчанию 1.2 м/с).
     */
    fun walkingTimeSeconds(
        distanceMeters: Double,
        speedMps: Double = AppConfig.WALKING_SPEED_MPS
    ): Int {
        if (distanceMeters <= 0.0) return 0
        return ceil(distanceMeters / speedMps).toInt().coerceAtLeast(15)
    }
}
