package pl.poznan.offdojade.data.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import pl.poznan.offdojade.domain.model.AddressResult
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Быстрый поиск адресов, улиц, домов и мест (POI) в Познани и агломерации (Зоны A–D):
 * 1. Photon (Komoot / OpenStreetMap) — мгновенный автокомплит для неполных названий улиц и номеров домов.
 * 2. OpenStreetMap Nominatim — резервный поиск в границах Познани и агломерации.
 * 3. Системный Android [Geocoder] — локальный резерв с таймаутом.
 * В офлайн-режиме безопасно возвращает пустой список без ошибок.
 */
class AddressSearchProvider(
    private val context: Context,
    baseHttpClient: OkHttpClient
) {
    private val httpClient: OkHttpClient = baseHttpClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, List<AddressResult>>()

    suspend fun searchAddresses(query: String, limit: Int = 6): List<AddressResult> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.length < 3) return@withContext emptyList()

            val cacheKey = "${trimmed.lowercase()}|$limit"
            cache[cacheKey]?.let { return@withContext it }

            val fromPhoton = queryPhoton(trimmed, limit)
            if (fromPhoton.isNotEmpty()) {
                cacheResult(cacheKey, fromPhoton)
                return@withContext fromPhoton
            }

            val fromNominatim = queryNominatim(trimmed, limit)
            if (fromNominatim.isNotEmpty()) {
                cacheResult(cacheKey, fromNominatim)
                return@withContext fromNominatim
            }

            val fromGeocoder = queryAndroidGeocoder(trimmed, limit)
            if (fromGeocoder.isNotEmpty()) {
                cacheResult(cacheKey, fromGeocoder)
            }
            fromGeocoder
        }

    private fun cacheResult(key: String, results: List<AddressResult>) {
        if (cache.size > 150) cache.clear()
        cache[key] = results
    }

    /**
     * Photon (Komoot) — лучший бесплатный автокомплит по адресам OSM с приоритетом Познани и агломерации.
     */
    private fun queryPhoton(query: String, limit: Int): List<AddressResult> {
        val encoded = Uri.encode(query)
        val url = "https://photon.komoot.io/api/" +
            "?q=$encoded" +
            "&lat=$POZNAN_CENTER_LAT&lon=$POZNAN_CENTER_LON" +
            "&bbox=$POZNAN_MIN_LON,$POZNAN_MIN_LAT,$POZNAN_MAX_LON,$POZNAN_MAX_LAT" +
            "&limit=$limit"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PyrTransit-Poznan/1.0")
            .header("Accept", "application/json")
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val body = resp.body?.string() ?: return@runCatching emptyList()
                val features = JSONObject(body).optJSONArray("features") ?: return@runCatching emptyList()
                val results = ArrayList<AddressResult>(features.length())

                for (i in 0 until features.length()) {
                    val feat = features.optJSONObject(i) ?: continue
                    val coords = feat.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                    if (coords.length() < 2) continue
                    val lon = coords.optDouble(0, Double.NaN)
                    val lat = coords.optDouble(1, Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue
                    if (lat !in POZNAN_MIN_LAT..POZNAN_MAX_LAT || lon !in POZNAN_MIN_LON..POZNAN_MAX_LON) continue

                    val props = feat.optJSONObject("properties") ?: continue
                    val name = props.optString("name").trim()
                    val street = props.optString("street").trim()
                    val house = props.optString("housenumber").trim()
                    val district = props.optString("district").trim()
                    val city = props.optString("city").trim()
                        .ifEmpty { props.optString("town").trim() }
                        .ifEmpty { props.optString("village").trim() }
                        .ifEmpty { props.optString("locality").trim() }

                    val streetWithHouse = listOf(street, house)
                        .filter { it.isNotEmpty() }
                        .joinToString(" ")

                    val title = when {
                        name.isNotEmpty() && streetWithHouse.isNotEmpty() && !name.equals(street, ignoreCase = true) ->
                            "$name ($streetWithHouse)"
                        streetWithHouse.isNotEmpty() -> streetWithHouse
                        name.isNotEmpty() -> name
                        else -> continue
                    }

                    val subtitle = listOf(district, city)
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString(", ")
                        .ifEmpty { "Poznań" }

                    results.add(
                        AddressResult(
                            id = "ph_${i}_${lat}_${lon}",
                            displayName = title,
                            subtitle = subtitle,
                            latitude = lat,
                            longitude = lon
                        )
                    )
                }

                results.distinctBy { "${it.displayName.lowercase()}|${it.subtitle.lowercase()}" }
            }
        }.getOrDefault(emptyList())
    }

    private fun queryNominatim(query: String, limit: Int): List<AddressResult> {
        val encoded = Uri.encode(query)
        val url = "https://nominatim.openstreetmap.org/search" +
            "?q=$encoded&format=json&addressdetails=1&limit=$limit" +
            "&bounded=1&viewbox=$POZNAN_MIN_LON,$POZNAN_MAX_LAT,$POZNAN_MAX_LON,$POZNAN_MIN_LAT"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PyrTransit-Poznan/1.0")
            .header("Accept", "application/json")
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList()
                val body = resp.body?.string() ?: return@runCatching emptyList()
                val arr = JSONArray(body)
                val results = ArrayList<AddressResult>(arr.length())

                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val lat = obj.optString("lat").toDoubleOrNull() ?: continue
                    val lon = obj.optString("lon").toDoubleOrNull() ?: continue
                    if (lat !in POZNAN_MIN_LAT..POZNAN_MAX_LAT || lon !in POZNAN_MIN_LON..POZNAN_MAX_LON) continue

                    val addrObj = obj.optJSONObject("address")
                    val road = addrObj?.optString("road").orEmpty().trim()
                    val house = addrObj?.optString("house_number").orEmpty().trim()
                    val city = addrObj?.optString("city").orEmpty().trim()
                        .ifEmpty { addrObj?.optString("town").orEmpty().trim() }
                        .ifEmpty { addrObj?.optString("village").orEmpty().trim() }
                        .ifEmpty { "Poznań" }
                    val suburb = addrObj?.optString("suburb").orEmpty().trim()

                    val title = if (road.isNotEmpty()) {
                        listOf(road, house).filter { it.isNotEmpty() }.joinToString(" ")
                    } else {
                        obj.optString("display_name").substringBefore(",").trim()
                    }
                    if (title.isEmpty()) continue
                    val subtitle = listOf(suburb, city)
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .joinToString(", ")

                    results.add(
                        AddressResult(
                            id = "nom_${i}_${lat}_${lon}",
                            displayName = title,
                            subtitle = subtitle,
                            latitude = lat,
                            longitude = lon
                        )
                    )
                }

                results.distinctBy { "${it.displayName.lowercase()}|${it.subtitle.lowercase()}" }
            }
        }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION")
    private suspend fun queryAndroidGeocoder(query: String, limit: Int): List<AddressResult> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context, Locale("pl", "PL"))

        return withTimeoutOrNull(2_500L) {
            try {
                val rawAddresses: List<Address> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocationName(
                            query,
                            limit,
                            POZNAN_MIN_LAT,
                            POZNAN_MIN_LON,
                            POZNAN_MAX_LAT,
                            POZNAN_MAX_LON,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) {
                                    if (cont.isActive) cont.resume(addresses)
                                }

                                override fun onError(errorMessage: String?) {
                                    if (cont.isActive) cont.resume(emptyList())
                                }
                            }
                        )
                    }
                } else {
                    geocoder.getFromLocationName(
                        query,
                        limit,
                        POZNAN_MIN_LAT,
                        POZNAN_MIN_LON,
                        POZNAN_MAX_LAT,
                        POZNAN_MAX_LON
                    ).orEmpty()
                }

                rawAddresses.mapIndexedNotNull { idx, addr ->
                    val lat = addr.latitude
                    val lon = addr.longitude
                    if (lat !in POZNAN_MIN_LAT..POZNAN_MAX_LAT || lon !in POZNAN_MIN_LON..POZNAN_MAX_LON) {
                        return@mapIndexedNotNull null
                    }
                    val street = listOfNotNull(addr.thoroughfare, addr.subThoroughfare)
                        .joinToString(" ")
                        .trim()
                    val title = street.ifEmpty {
                        addr.featureName ?: addr.getAddressLine(0)?.substringBefore(",") ?: return@mapIndexedNotNull null
                    }
                    val subtitle = listOfNotNull(addr.subLocality, addr.locality)
                        .distinct()
                        .joinToString(", ")
                        .ifEmpty { "Poznań" }
                    AddressResult(
                        id = "geo_${idx}_${lat}_${lon}",
                        displayName = title,
                        subtitle = subtitle,
                        latitude = lat,
                        longitude = lon
                    )
                }.distinctBy { "${it.displayName.lowercase()}|${it.subtitle.lowercase()}" }
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }

    companion object {
        private const val POZNAN_CENTER_LAT = 52.4064
        private const val POZNAN_CENTER_LON = 16.9252
        private const val POZNAN_MIN_LAT = 52.15
        private const val POZNAN_MAX_LAT = 52.62
        private const val POZNAN_MIN_LON = 16.45
        private const val POZNAN_MAX_LON = 17.35
    }
}
