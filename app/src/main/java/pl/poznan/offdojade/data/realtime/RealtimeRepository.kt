package pl.poznan.offdojade.data.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import pl.poznan.offdojade.config.AppConfig
import java.util.concurrent.ConcurrentHashMap

sealed interface RealtimeStatus {
    data class Live(
        val lastUpdatedEpochMs: Long,
        val trackedTrips: Int,
        val activeVehicles: Int
    ) : RealtimeStatus

    data class Offline(
        val reason: String = "Нет связи с ZTM"
    ) : RealtimeStatus
}

data class LiveVehicle(
    val vehicleId: String,
    val label: String,
    val tripId: String?,
    val routeId: String?,
    val latitude: Double,
    val longitude: Double,
    val bearing: Float?,
    val timestampEpochSec: Long?
)

/**
 * Репозиторий живых данных ZTM Poznań (GTFS-RT).
 * Хранит задержки в `ConcurrentHashMap<String, Int>` (`trip_id -> delay_seconds`)
 * и обеспечивает Graceful Fallback в офлайн-режим без ошибок и крашей.
 */
class RealtimeRepository(
    private val client: GtfsRtClient
) {
    /** Карта задержек `trip_id -> delay_seconds` для быстрого lock-free доступа из RAPTOR и табло */
    val tripDelaysSec: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    private val _status = MutableStateFlow<RealtimeStatus>(RealtimeStatus.Offline("Ожидание синхронизации"))
    val status: StateFlow<RealtimeStatus> = _status.asStateFlow()

    private val _vehicles = MutableStateFlow<List<LiveVehicle>>(emptyList())
    val vehicles: StateFlow<List<LiveVehicle>> = _vehicles.asStateFlow()

    /**
     * Запускает бесконечный цикл опроса каждые 20 секунд (вызывать внутри `repeatOnLifecycle(STARTED)`).
     */
    suspend fun pollLoop() {
        withContext(Dispatchers.IO) {
            while (isActive) {
                refreshOnce()
                delay(AppConfig.GTFS_RT_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Однократное обновление `trip_updates.pb` и `vehicle_positions.pb` с безопасным fallback.
     */
    suspend fun refreshOnce(): Boolean = withContext(Dispatchers.IO) {
        try {
            val tripFeed = client.fetchTripUpdates()
            val updatedDelays = HashMap<String, Int>(tripFeed.entity.size)

            for (entity in tripFeed.entity) {
                val tu = entity.trip_update ?: continue
                val tripId = tu.trip.trip_id ?: continue

                // Приоритет: ненулевая общая задержка -> последняя актуальная задержка в stop_time_update -> 0
                // Отрицательные значения (автобус ждёт на конечной до старта рейса) обнуляем, чтобы не показывать ложное «опережение»
                val stuDelay = tu.stop_time_update
                    .lastOrNull { (it.departure?.delay ?: it.arrival?.delay) != null }
                    ?.let { it.departure?.delay ?: it.arrival?.delay }
                val rawDelay = when {
                    tu.delay != null && tu.delay != 0 -> tu.delay
                    stuDelay != null -> stuDelay
                    else -> tu.delay ?: 0
                }
                updatedDelays[tripId] = rawDelay.coerceAtLeast(0)
            }

            tripDelaysSec.clear()
            tripDelaysSec.putAll(updatedDelays)

            val vehicleList = try {
                val vehicleFeed = client.fetchVehiclePositions()
                vehicleFeed.entity.mapNotNull { entity ->
                    val vp = entity.vehicle ?: return@mapNotNull null
                    val pos = vp.position ?: return@mapNotNull null
                    LiveVehicle(
                        vehicleId = vp.vehicle?.id ?: entity.id,
                        label = vp.vehicle?.label ?: vp.trip?.route_id.orEmpty(),
                        tripId = vp.trip?.trip_id,
                        routeId = vp.trip?.route_id,
                        latitude = pos.latitude.toDouble(),
                        longitude = pos.longitude.toDouble(),
                        bearing = pos.bearing,
                        timestampEpochSec = vp.timestamp
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _vehicles.value
            }

            _vehicles.value = vehicleList
            _status.value = RealtimeStatus.Live(
                lastUpdatedEpochMs = System.currentTimeMillis(),
                trackedTrips = tripDelaysSec.size,
                activeVehicles = vehicleList.size
            )
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Graceful fallback: не сбрасываем приложение, переходим в статус Offline
            _status.value = RealtimeStatus.Offline(
                reason = e.localizedMessage ?: "Офлайн-режим"
            )
            false
        }
    }

    fun getDelaySeconds(tripId: String): Int = tripDelaysSec[tripId] ?: 0

    fun isTripTracked(tripId: String): Boolean = tripDelaysSec.containsKey(tripId)
}
