package pl.poznan.offdojade.data.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import pl.poznan.offdojade.MainActivity
import pl.poznan.offdojade.R
import pl.poznan.offdojade.data.realtime.LiveVehicle
import pl.poznan.offdojade.data.realtime.RealtimeRepository
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.TransitJourney
import pl.poznan.offdojade.data.gtfs.GeoUtils
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import pl.poznan.offdojade.ui.components.TimeFormatUtils
import java.time.LocalTime
import java.time.ZoneId

/**
 * Foreground-сервис динамического отслеживания поездки в уведомлении.
 * Каждые 15 секунд обновляет:
 * - где сейчас находится автобус/трамвай (по GTFS-RT GPS/stop_id или по расписанию в оффлайне),
 * - сколько остановок и минут осталось до пересадки или выхода,
 * - прогресс-бар маршрута, с кнопкой быстрой остановки.
 */
class TripTrackingService : Service() {

    private val transitGraphRepository: TransitGraphRepository by inject()
    private val realtimeRepository: RealtimeRepository by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var trackingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }

        val journey = transitGraphRepository.selectedJourney.value
        if (journey == null) {
            stopTracking()
            return START_NOT_STICKY
        }

        OffDojadeNotifications.ensureChannels(this)
        _trackedJourneyId.value = journey.id

        val initialNotification = buildTrackingNotification(
            journey = journey,
            vehicles = realtimeRepository.vehicles.value
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                OffDojadeNotifications.NOTIFICATION_ID_TRIP_TRACKING,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(
                OffDojadeNotifications.NOTIFICATION_ID_TRIP_TRACKING,
                initialNotification
            )
        }

        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            while (isActive) {
                runCatching { realtimeRepository.refreshOnce() }
                val notification = buildTrackingNotification(
                    journey = journey,
                    vehicles = realtimeRepository.vehicles.value
                )
                if (OffDojadeNotifications.canPostNotifications(this@TripTrackingService)) {
                    runCatching {
                        NotificationManagerCompat.from(this@TripTrackingService)
                            .notify(OffDojadeNotifications.NOTIFICATION_ID_TRIP_TRACKING, notification)
                    }
                }

                // Автоматически останавливаем отслеживание через 3 минуты после прибытия
                val nowSec = LocalTime.now(ZoneId.of("Europe/Warsaw")).toSecondOfDay()
                val arrMod = journey.arrivalTimeSec % 86_400
                if (nowSec > arrMod + 180 && nowSec - arrMod < 3600) {
                    stopTracking()
                    break
                }
                delay(15_000L)
            }
        }

        return START_NOT_STICKY
    }

    private fun stopTracking() {
        trackingJob?.cancel()
        trackingJob = null
        _trackedJourneyId.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _trackedJourneyId.value = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildTrackingNotification(
        journey: TransitJourney,
        vehicles: List<LiveVehicle>
    ): Notification {
        val nowSec = LocalTime.now(ZoneId.of("Europe/Warsaw")).toSecondOfDay()
        val transitLegs = journey.transitLegs
        val totalStart = (journey.departureTimeSec % 86_400)
        val totalEnd = (journey.arrivalTimeSec % 86_400)
        val totalSpan = (totalEnd - totalStart).coerceAtLeast(60)
        val progressPct = (((nowSec - totalStart).toFloat() / totalSpan.toFloat()) * 100f)
            .toInt()
            .coerceIn(0, 100)

        val status = computeLiveTripStatus(
            transitLegs = transitLegs,
            vehicles = vehicles,
            nowSec = nowSec
        )

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TripTrackingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, OffDojadeNotifications.CHANNEL_TRIP_TRACKING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(status.title)
            .setContentText(status.shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status.expandedText))
            .setProgress(100, progressPct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private data class TripNotificationStatus(
        val title: String,
        val shortText: String,
        val expandedText: String
    )

    private fun computeLiveTripStatus(
        transitLegs: List<JourneyLeg.TransitLeg>,
        vehicles: List<LiveVehicle>,
        nowSec: Int
    ): TripNotificationStatus {
        if (transitLegs.isEmpty()) {
            return TripNotificationStatus(
                title = "PyrTransit • Маршрут",
                shortText = "Следование по маршруту",
                expandedText = "Следование по маршруту"
            )
        }

        // Определяем текущий или ближайший сегмент поездки
        for ((idx, leg) in transitLegs.withIndex()) {
            val delaySec = realtimeRepository.tripDelaysSec[leg.tripId] ?: leg.delaySeconds
            val depSec = (leg.boardingStop.scheduledTimeSec % 86_400) + delaySec
            val arrSec = (leg.alightingStop.scheduledTimeSec % 86_400) + delaySec
            val icon = if (leg.isTram) "🚋" else "🚌"
            val nextLeg = transitLegs.getOrNull(idx + 1)
            val delayTag = if (delaySec >= 60) " (+${delaySec / 60} мин)" else ""

            // 1. Ещё не сели в этот транспорт
            if (nowSec < depSec) {
                val minsToBoard = ((depSec - nowSec) + 30) / 60
                val waitText = if (minsToBoard <= 0) "<1 мин" else "$minsToBoard мин"
                val title = "$icon ${leg.routeShortName} → ${leg.headsign}"
                val short = if (idx == 0) {
                    "Посадка через $waitText • ${leg.boardingStop.stopName}$delayTag"
                } else {
                    "Пересадка • ${leg.routeShortName} через $waitText (${leg.boardingStop.stopName})$delayTag"
                }
                val expanded = buildString {
                    append(short)
                    append("\nВыход: ${leg.alightingStop.stopName} (${TimeFormatUtils.formatClock(arrSec)})")
                }
                return TripNotificationStatus(title, short, expanded)
            }

            // 2. Сейчас едем в этом транспорте
            if (nowSec <= arrSec) {
                val matchedVehicle = vehicles.firstOrNull { it.tripId == leg.tripId }
                val currentStopIdx = resolveCurrentStopIndex(leg, matchedVehicle, nowSec, delaySec)
                val currentStopName = leg.stops.getOrNull(currentStopIdx)?.stopName ?: leg.boardingStop.stopName
                val stopsRemaining = (leg.stops.lastIndex - currentStopIdx).coerceAtLeast(0)
                val minsRemaining = ((arrSec - nowSec) + 30) / 60

                val title = "$icon ${leg.routeShortName} • Сейчас: $currentStopName"
                val actionLine = when {
                    stopsRemaining <= 1 && nextLeg != null ->
                        "Выходите на следующей (${leg.alightingStop.stopName}) → пересадка на ${nextLeg.routeShortName}!"
                    stopsRemaining <= 1 ->
                        "Выходите на следующей остановке: ${leg.alightingStop.stopName}!"
                    nextLeg != null ->
                        "Пересадка через $stopsRemaining ост. ($minsRemaining мин) на ${leg.alightingStop.stopName} → ${nextLeg.routeShortName}"
                    else ->
                        "Выход через $stopsRemaining ост. ($minsRemaining мин) на ${leg.alightingStop.stopName}"
                }
                val liveTag = if (matchedVehicle != null) "GPS • " else ""
                val expanded = buildString {
                    append("${liveTag}Сейчас: $currentStopName$delayTag")
                    append("\n$actionLine")
                }
                return TripNotificationStatus(title, actionLine, expanded)
            }
        }

        // 3. Все сегменты завершены — прибыли!
        val lastStop = transitLegs.last().alightingStop.stopName
        return TripNotificationStatus(
            title = "✓ Вы прибыли • $lastStop",
            shortText = "Поездка завершена",
            expandedText = "Вы прибыли на остановку $lastStop"
        )
    }

    /**
     * Определяет индекс текущей остановки на сегменте:
     * 1) по `stop_id` из GTFS-RT,
     * 2) по ближайшей остановке к GPS-координатам `LiveVehicle`,
     * 3) по расписанию (если оффлайн).
     */
    private fun resolveCurrentStopIndex(
        leg: JourneyLeg.TransitLeg,
        vehicle: LiveVehicle?,
        nowSec: Int,
        delaySec: Int
    ): Int {
        if (leg.stops.isEmpty()) return 0

        if (vehicle?.currentStopId != null) {
            val byId = leg.stops.indexOfFirst { it.stopId == vehicle.currentStopId }
            if (byId >= 0) return byId
        }

        if (vehicle != null) {
            var bestIdx = 0
            var bestDist = Double.MAX_VALUE
            leg.stops.forEachIndexed { i, stop ->
                val d = GeoUtils.haversineMeters(vehicle.latitude, vehicle.longitude, stop.latitude, stop.longitude)
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = i
                }
            }
            if (bestDist <= 1_500.0) return bestIdx
        }

        var idx = 0
        for (i in leg.stops.indices) {
            val eff = (leg.stops[i].scheduledTimeSec % 86_400) + delaySec
            if (nowSec >= eff) idx = i else break
        }
        return idx
    }

    companion object {
        private const val ACTION_START = "pl.poznan.offdojade.action.START_TRACKING"
        private const val ACTION_STOP = "pl.poznan.offdojade.action.STOP_TRACKING"

        private val _trackedJourneyId = MutableStateFlow<String?>(null)
        val trackedJourneyId: StateFlow<String?> = _trackedJourneyId.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, TripTrackingService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TripTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
