package pl.poznan.offdojade.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.collect
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.gtfs.GtfsImporter
import pl.poznan.offdojade.data.gtfs.PrebuiltDbDownloader
import pl.poznan.offdojade.data.local.PreferencesRepository
import pl.poznan.offdojade.data.notifications.OffDojadeNotifications
import pl.poznan.offdojade.domain.routing.TransitGraphRepository
import java.util.concurrent.TimeUnit

/**
 * Фоновое ежедневное обновление статических расписаний ZTM Poznań по Wi-Fi (UNMETERED).
 * Если настроен GitHub Releases — проверяет крошечный `version.json` (100 байт) и скачивает
 * готовую сжатую SQLite-базу за ~1 сек. Иначе откатывается на прямой импорт с сервера ZTM.
 */
class GtfsSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val prebuiltDbDownloader: PrebuiltDbDownloader by inject()
    private val gtfsImporter: GtfsImporter by inject()
    private val preferencesRepository: PreferencesRepository by inject()
    private val transitGraphRepository: TransitGraphRepository by inject()

    override suspend fun doWork(): Result {
        OffDojadeNotifications.showBackgroundSyncNotification(applicationContext)
        return try {
            // 1. Приоритет: быстрый предсобранный SQLite из GitHub Actions (version.json + poznan_schedule.db.gz)
            if (AppConfig.isPrebuiltScheduleConfigured()) {
                val prebuiltOk = runCatching {
                    prebuiltDbDownloader.syncFromGitHubReleases(forceDownload = false).collect()
                    transitGraphRepository.reloadForToday(preferDiskCache = true)
                }.isSuccess
                if (prebuiltOk) return Result.success()
            }

            // 2. Fallback: прямое скачивание и потоковый парсинг GTFS ZIP с ZTM Poznań
            val localMeta = preferencesRepository.getSyncMetadata()
            val remoteHeaders = gtfsImporter.fetchRemoteHeaders()

            val needsUpdate = when {
                remoteHeaders == null -> true
                remoteHeaders.etag != null && remoteHeaders.etag != localMeta.etag -> true
                remoteHeaders.lastModified != null && remoteHeaders.lastModified != localMeta.lastModified -> true
                remoteHeaders.archiveName != null && remoteHeaders.archiveName != localMeta.archiveName -> true
                localMeta.lastSyncEpochMs == 0L -> true
                else -> false
            }

            if (needsUpdate) {
                gtfsImporter.downloadAndImport().collect()
                transitGraphRepository.reloadForToday()
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            OffDojadeNotifications.cancelBackgroundSyncNotification(applicationContext)
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "ztm_poznan_background_gtfs_sync"

        /**
         * Планирует периодическое фоновое обновление оффлайн-базы расписаний (раз в 12 часов)
         * при наличии любого интернет-соединения и достаточного заряда батареи.
         */
        fun scheduleBackgroundSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<GtfsSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
