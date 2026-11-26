package pl.poznan.offdojade.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import pl.poznan.offdojade.config.AppConfig

data class UserLocation(
    val latitude: Double,
    val longitude: Double,
    val isFallbackCenter: Boolean = false
)

class LocationProvider(private val context: Context) {

    private val fusedClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /**
     * Активно запрашивает свежую высокоточную GPS-позицию (до 3.5 сек),
     * при таймауте использует lastLocation или центр Познани.
     */
    @SuppressLint("MissingPermission")
    suspend fun getFreshOrFallbackLocation(): UserLocation {
        if (!hasLocationPermission()) {
            return fallbackLocation()
        }
        return try {
            val fresh = withTimeoutOrNull(3_500L) {
                val cts = CancellationTokenSource()
                fusedClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cts.token
                ).await()
            }
            if (fresh != null) {
                UserLocation(fresh.latitude, fresh.longitude, isFallbackCenter = false)
            } else {
                val last = fusedClient.lastLocation.await()
                if (last != null) {
                    UserLocation(last.latitude, last.longitude, isFallbackCenter = false)
                } else {
                    fallbackLocation()
                }
            }
        } catch (_: Exception) {
            fallbackLocation()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentOrFallbackLocation(): UserLocation {
        if (!hasLocationPermission()) {
            return fallbackLocation()
        }
        return try {
            val last = fusedClient.lastLocation.await()
            val isFreshEnough = last != null && (System.currentTimeMillis() - last.time) < 60_000L
            if (isFreshEnough && last != null) {
                UserLocation(last.latitude, last.longitude, isFallbackCenter = false)
            } else {
                val fresh = withTimeoutOrNull(3_000L) {
                    val cts = CancellationTokenSource()
                    fusedClient.getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        cts.token
                    ).await()
                }
                when {
                    fresh != null -> UserLocation(fresh.latitude, fresh.longitude, isFallbackCenter = false)
                    last != null -> UserLocation(last.latitude, last.longitude, isFallbackCenter = false)
                    else -> fallbackLocation()
                }
            }
        } catch (_: Exception) {
            fallbackLocation()
        }
    }

    private fun fallbackLocation() = UserLocation(
        latitude = AppConfig.POZNAN_CENTER_LAT,
        longitude = AppConfig.POZNAN_CENTER_LON,
        isFallbackCenter = true
    )
}
