package pl.poznan.offdojade.data.realtime

import okhttp3.OkHttpClient
import okhttp3.Request
import pl.poznan.offdojade.config.AppConfig
import pl.poznan.offdojade.gtfsrt.FeedMessage
import java.io.IOException

/**
 * Клиент загрузки и бинарного декодирования GTFS-RT (.pb) через Square Wire.
 */
class GtfsRtClient(
    private val okHttpClient: OkHttpClient
) {
    fun fetchTripUpdates(
        url: String = AppConfig.ZTM_GTFS_RT_TRIP_UPDATES_URL
    ): FeedMessage = fetchFeed(url)

    fun fetchVehiclePositions(
        url: String = AppConfig.ZTM_GTFS_RT_VEHICLE_POSITIONS_URL
    ): FeedMessage = fetchFeed(url)

    private fun fetchFeed(url: String): FeedMessage {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/x-protobuf, application/octet-stream")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GTFS-RT HTTP ${response.code} для $url")
            }
            val bytes = response.body?.bytes()
                ?: throw IOException("Пустой ответ GTFS-RT от $url")
            return FeedMessage.ADAPTER.decode(bytes)
        }
    }
}
