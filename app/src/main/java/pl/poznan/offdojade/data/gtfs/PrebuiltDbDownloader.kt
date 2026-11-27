package pl.poznan.offdojade.data.gtfs

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.data.local.DatabaseHolder
import pl.poznan.offdojade.data.local.PreferencesRepository
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

@Serializable
data class PrebuiltScheduleVersion(
    @SerialName("build_date") val buildDate: String,
    @SerialName("sha256") val sha256: String,
    @SerialName("db_filename") val dbFilename: String = "poznan_schedule.db.gz",
    @SerialName("size_bytes") val sizeBytes: Long = 0L,
    @SerialName("compressed_size_bytes") val compressedSizeBytes: Long = 0L,
    @SerialName("stops_count") val stopsCount: Int = 0,
    @SerialName("trips_count") val tripsCount: Int = 0,
    @SerialName("room_version") val roomVersion: Int = 1
)

/**
 * Быстрое обновление расписания из GitHub Releases (результат ночного GitHub Actions):
 * 1. Скачивает крошечный `version.json` (~100 байт) и проверяет SHA-256.
 * 2. Если база обновилась — скачивает `poznan_schedule.db.gz`, на лету распаковывает
 *    через `GZIPInputStream`, сверяет контрольную сумму и атомарно подменяет локальную базу Room.
 */
class PrebuiltDbDownloader(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val databaseHolder: DatabaseHolder,
    private val preferencesRepository: PreferencesRepository
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetchRemoteVersion(
        url: String = AppConfig.SCHEDULE_VERSION_URL
    ): PrebuiltScheduleVersion? {
        if (!AppConfig.isPrebuiltScheduleConfigured()) return null
        val request = Request.Builder().url(url).build()
        return try {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                json.decodeFromString<PrebuiltScheduleVersion>(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Проверяет `version.json` и, если вышла новая сборка, скачивает и подменяет базу за ~1 секунду.
     * Возвращает `Flow<GtfsImportProgress>` для единого отображения прогресса в UI.
     */
    fun syncFromGitHubReleases(
        forceDownload: Boolean = false
    ): Flow<GtfsImportProgress> = flow {
        emit(GtfsImportProgress("Проверка version.json на GitHub…", 0.05f))

        val remote = fetchRemoteVersion()
            ?: throw IOException("Не удалось получить version.json из GitHub Releases")

        val localHash = preferencesRepository.getSyncMetadata().prebuiltDbSha256
        val dbHasData = databaseHolder.dao.getStopCount() > 0

        if (!forceDownload && dbHasData && localHash != null && localHash.equals(remote.sha256, ignoreCase = true)) {
            emit(GtfsImportProgress("База расписания уже актуальна (${remote.buildDate.take(10)})", 1f, isFinished = true))
            return@flow
        }

        emit(GtfsImportProgress("Скачивание готовой базы poznan_schedule.db.gz…", 0.20f))

        val tmpFile = File(context.cacheDir, "poznan_schedule_download.db.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        val request = Request.Builder().url(AppConfig.SCHEDULE_DB_GZ_URL).build()
        try {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code} при скачивании poznan_schedule.db.gz")
                }
                val body = resp.body ?: throw IOException("Пустой ответ poznan_schedule.db.gz")
                val expectedBytes = remote.sizeBytes.coerceAtLeast(1L)

                val digest = MessageDigest.getInstance("SHA-256")
                var writtenBytes = 0L
                var lastEmit = 0L
                val buffer = ByteArray(128 * 1024)

                GZIPInputStream(BufferedInputStream(body.byteStream(), 256 * 1024), 256 * 1024).use { gzis ->
                    DigestOutputStream(BufferedOutputStream(FileOutputStream(tmpFile), 256 * 1024), digest).use { out ->
                        while (true) {
                            val read = gzis.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            writtenBytes += read

                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 120L) {
                                lastEmit = now
                                val ratio = (writtenBytes.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f)
                                emit(
                                    GtfsImportProgress(
                                        stage = "Распаковка готовой базы…",
                                        progress = 0.20f + ratio * 0.65f,
                                        rowsImported = remote.tripsCount
                                    )
                                )
                            }
                        }
                        out.flush()
                    }
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (remote.sha256.isNotBlank() && !actualSha256.equals(remote.sha256, ignoreCase = true)) {
                    throw IOException("Несовпадение SHA-256 базы расписаний")
                }
            }

            emit(GtfsImportProgress("Подмена локальной базы SQLite…", 0.92f, rowsImported = remote.tripsCount))
            databaseHolder.replaceDatabaseFile(tmpFile)

            preferencesRepository.updatePrebuiltDbMetadata(
                sha256 = remote.sha256,
                buildDate = remote.buildDate
            )
            emit(
                GtfsImportProgress(
                    stage = "Готовая база обновлена (${remote.stopsCount} остановок)",
                    progress = 1f,
                    rowsImported = remote.tripsCount,
                    isFinished = true
                )
            )
        } catch (e: Exception) {
            if (tmpFile.exists()) tmpFile.delete()
            throw e
        }
    }.flowOn(Dispatchers.IO)
}
