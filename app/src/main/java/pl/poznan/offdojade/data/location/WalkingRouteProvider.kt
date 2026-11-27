package pl.poznan.offdojade.data.location

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class WalkingRouteResult(
    val points: List<Pair<Double, Double>>,
    val distanceMeters: Int?
)

/**
 * Строит пешие маршруты по реальным дорогам и тротуарам через OSRM Foot Routing API
 * (OpenStreetMap) и возвращает реальную длину пешего пути в метрах.
 * При отсутствии интернета или таймауте мгновенно возвращает прямую линию.
 */
class WalkingRouteProvider(
    baseHttpClient: OkHttpClient
) {
    private val client: OkHttpClient = baseHttpClient.newBuilder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .callTimeout(3500, TimeUnit.MILLISECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, WalkingRouteResult>()

    suspend fun getWalkingPolyline(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): List<Pair<Double, Double>> = getWalkingRoute(fromLat, fromLon, toLat, toLon).points

    suspend fun getWalkingRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): WalkingRouteResult = withContext(Dispatchers.IO) {
        val fallback = WalkingRouteResult(
            points = listOf(fromLat to fromLon, toLat to toLon),
            distanceMeters = null
        )
        val cacheKey = String.format(
            Locale.US,
            "%.5f,%.5f->%.5f,%.5f",
            fromLat, fromLon, toLat, toLon
        )
        cache[cacheKey]?.let { return@withContext it }

        val url = String.format(
            Locale.US,
            "https://routing.openstreetmap.de/routed-foot/route/v1/foot/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
            fromLon, fromLat, toLon, toLat
        )

        val routed = runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "PyrTransit-Poznan/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val root = JSONObject(body)
                val routes = root.optJSONArray("routes") ?: return@runCatching null
                if (routes.length() == 0) return@runCatching null
                val route0 = routes.getJSONObject(0)
                val osrmDist = route0.optDouble("distance", -1.0)
                val geometry = route0.optJSONObject("geometry") ?: return@runCatching null
                val coords = geometry.optJSONArray("coordinates") ?: return@runCatching null
                if (coords.length() < 2) return@runCatching null

                val points = ArrayList<Pair<Double, Double>>(coords.length())
                for (i in 0 until coords.length()) {
                    val pair = coords.getJSONArray(i)
                    val lon = pair.getDouble(0)
                    val lat = pair.getDouble(1)
                    points.add(lat to lon)
                }
                WalkingRouteResult(
                    points = points,
                    distanceMeters = if (osrmDist > 0.0) osrmDist.toInt() else null
                )
            }
        }.getOrNull()

        if (routed != null && routed.points.size >= 2) {
            if (cache.size > 200) cache.clear()
            cache[cacheKey] = routed
            routed
        } else {
            fallback
        }
    }
}
