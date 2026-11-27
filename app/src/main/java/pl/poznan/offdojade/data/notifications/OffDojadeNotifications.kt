package pl.poznan.offdojade.data.notifications

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Централизованное управление каналами и уведомлениями OffDojade:
 * 1. Фоновое обновление расписания (показывается, когда приложение не на переднем плане).
 * 2. Динамическое отслеживание поездки (текущая остановка, пересадка, выход).
 */
object OffDojadeNotifications {
    const val CHANNEL_BG_SYNC = "offdojade_bg_sync"
    const val CHANNEL_TRIP_TRACKING = "offdojade_trip_tracking"

    const val NOTIFICATION_ID_BG_SYNC = 4101
    const val NOTIFICATION_ID_TRIP_TRACKING = 4201

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val syncChannel = NotificationChannel(
            CHANNEL_BG_SYNC,
            "Фоновое обновление расписания",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Показывает статус автоматического обновления расписания ZTM Poznań в фоне"
            setShowBadge(false)
        }

        val tripChannel = NotificationChannel(
            CHANNEL_TRIP_TRACKING,
            "Отслеживание поездки",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Динамическое уведомление о текущем положении транспорта, пересадках и выходе"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        manager.createNotificationChannel(syncChannel)
        manager.createNotificationChannel(tripChannel)
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isAppInForeground(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val appProcesses = am.runningAppProcesses ?: return false
        val pkg = context.packageName
        return appProcesses.any {
            it.processName == pkg &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    fun showBackgroundSyncNotification(context: Context) {
        if (!canPostNotifications(context) || isAppInForeground(context)) return
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_BG_SYNC)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PyrTransit • ZTM Poznań")
            .setContentText("Идёт фоновое обновление расписания…")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_BG_SYNC, notification)
        }
    }

    fun cancelBackgroundSyncNotification(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_BG_SYNC)
        }
    }
}
