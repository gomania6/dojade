package pl.poznan.offdojade.data.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.poznan.offdojade.config.AppConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class MapDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
    val downloadedMb: Double,
    val totalMb: Double,
    val isCompleted: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Загрузчик офлайн-векторной карты `poznan.pmtiles` с GitHub Releases.
 * Скачивает поток через OkHttp во временный файл `.tmp` и атомарно переименовывает
 * в `context.filesDir/poznan.pmtiles` после успешной загрузки.
 */
class MapDownloader(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    fun getLocalMapFile(): File = File(context.filesDir, AppConfig.LOCAL_MAP_FILENAME)

    fun isMapDownloaded(): Boolean {
        val file = getLocalMapFile()
        return file.exists() && file.length() > 0L
    }

    fun downloadMap(
        url: String = AppConfig.MAP_DOWNLOAD_URL
    ): Flow<MapDownloadProgress> = flow {
        val targetFile = getLocalMapFile()
        if (targetFile.exists() && targetFile.length() > 0L) {
            val mb = targetFile.length() / BYTES_PER_MB
            emit(
                MapDownloadProgress(
                    downloadedBytes = targetFile.length(),
                    totalBytes = targetFile.length(),
                    percent = 100,
                    downloadedMb = mb,
                    totalMb = mb,
                    isCompleted = true
                )
            )
            return@flow
        }

        val tmpFile = File(context.filesDir, "${AppConfig.LOCAL_MAP_FILENAME}.tmp")
        if (tmpFile.exists()) {
            tmpFile.delete()
        }

        val request = Request.Builder()
            .url(url)
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} при скачивании карты")
                }
                val body = response.body ?: throw IOException("Пустой ответ при загрузке карты")
                val totalBytes = body.contentLength()
                val totalMb = if (totalBytes > 0) totalBytes / BYTES_PER_MB else 0.0

                var downloaded = 0L
                var lastEmitTime = 0L
                val buffer = ByteArray(64 * 1024)

                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= 150L) {
                                lastEmitTime = now
                                val percent = if (totalBytes > 0) {
                                    ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                } else {
                                    0
                                }
                                emit(
                                    MapDownloadProgress(
                                        downloadedBytes = downloaded,
                                        totalBytes = totalBytes,
                                        percent = percent,
                                        downloadedMb = downloaded / BYTES_PER_MB,
                                        totalMb = totalMb
                                    )
                                )
                            }
                        }
                        output.fd.sync()
                    }
                }

                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tmpFile.renameTo(targetFile)) {
                    throw IOException("Не удалось атомарно сохранить ${AppConfig.LOCAL_MAP_FILENAME}")
                }

                val finalMb = targetFile.length() / BYTES_PER_MB
                emit(
                    MapDownloadProgress(
                        downloadedBytes = targetFile.length(),
                        totalBytes = targetFile.length(),
                        percent = 100,
                        downloadedMb = finalMb,
                        totalMb = finalMb,
                        isCompleted = true
                    )
                )
            }
        } catch (e: Exception) {
            if (tmpFile.exists()) tmpFile.delete()
            throw e
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
