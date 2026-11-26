package pl.poznan.offdojade.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
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

    @SuppressLint("MissingPermission")
    suspend fun getCurrentOrFallbackLocation(): UserLocation {
        if (!hasLocationPermission()) {
            return fallbackLocation()
        }
        return try {
            val last = fusedClient.lastLocation.await()
            if (last != null) {
                UserLocation(last.latitude, last.longitude, isFallbackCenter = false)
            } else {
                val cts = CancellationTokenSource()
                val current = fusedClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    cts.token
                ).await()
                if (current != null) {
                    UserLocation(current.latitude, current.longitude, isFallbackCenter = false)
                } else {
                    fallbackLocation()
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
