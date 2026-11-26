package pl.poznan.offdojade.data.location

import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.poznan.offdojade.domain.model.AddressResult
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Поиск адресов в Познани и агломерации (улица, дом, место).
 * Использует системный Android [Geocoder] с ограничением области Познани,
 * а при отсутствии результатов — резервный запрос к OpenStreetMap Nominatim (при наличии сети).
 * В офлайн-режиме безопасно возвращает пустой список без ошибок.
 */
class AddressSearchProvider(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun searchAddresses(query: String, limit: Int = 5): List<AddressResult> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.length < 3) return@withContext emptyList()

            val fromGeocoder = queryAndroidGeocoder(trimmed, limit)
            if (fromGeocoder.isNotEmpty()) return@withContext fromGeocoder

            queryNominatim(trimmed, limit)
        }

    @Suppress("DEPRECATION")
    private suspend fun queryAndroidGeocoder(query: String, limit: Int): List<AddressResult> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(context, Locale("pl", "PL"))
        val fullQuery = if (query.contains("poznan", ignoreCase = true) || query.contains("poznań", ignoreCase = true)) {
            query
        } else {
            "$query, Poznań"
        }

        return try {
            val rawAddresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(
                        fullQuery,
                        limit,
                        POZNAN_MIN_LAT,
                        POZNAN_MIN_LON,
                        POZNAN_MAX_LAT,
                        POZNAN_MAX_LON
                    ) { addresses ->
                        if (cont.isActive) cont.resume(addresses)
                    }
                }
            } else {
                geocoder.getFromLocationName(
                    fullQuery,
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
    }

    private fun queryNominatim(query: String, limit: Int): List<AddressResult> {
        val encoded = Uri.encode("$query, Poznań")
        val url = "https://nominatim.openstreetmap.org/search" +
            "?q=$encoded&format=json&addressdetails=1&limit=$limit" +
            "&bounded=1&viewbox=$POZNAN_MIN_LON,$POZNAN_MAX_LAT,$POZNAN_MAX_LON,$POZNAN_MIN_LAT"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "OffDojade-Poznan/1.0")
            .header("Accept", "application/json")
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val arr = json.parseToJsonElement(body).jsonArray
                arr.mapIndexedNotNull { idx, elem ->
                    val obj = elem.jsonObject
                    val lat = obj["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapIndexedNotNull null
                    val lon = obj["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapIndexedNotNull null
                    val addrObj = obj["address"]?.jsonObject
                    val road = addrObj?.get("road")?.jsonPrimitive?.content
                    val house = addrObj?.get("house_number")?.jsonPrimitive?.content
                    val city = addrObj?.get("city")?.jsonPrimitive?.content
                        ?: addrObj?.get("town")?.jsonPrimitive?.content
                        ?: addrObj?.get("village")?.jsonPrimitive?.content
                        ?: "Poznań"
                    val suburb = addrObj?.get("suburb")?.jsonPrimitive?.content

                    val title = if (!road.isNullOrBlank()) {
                        listOfNotNull(road, house).joinToString(" ")
                    } else {
                        obj["display_name"]?.jsonPrimitive?.content?.substringBefore(",") ?: return@mapIndexedNotNull null
                    }
                    val subtitle = listOfNotNull(suburb, city).distinct().joinToString(", ")

                    AddressResult(
                        id = "nom_${idx}_${lat}_${lon}",
                        displayName = title,
                        subtitle = subtitle,
                        latitude = lat,
                        longitude = lon
                    )
                }.distinctBy { "${it.displayName.lowercase()}|${it.subtitle.lowercase()}" }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val POZNAN_MIN_LAT = 52.15
        private const val POZNAN_MAX_LAT = 52.62
        private const val POZNAN_MIN_LON = 16.45
        private const val POZNAN_MAX_LON = 17.35
    }
}
