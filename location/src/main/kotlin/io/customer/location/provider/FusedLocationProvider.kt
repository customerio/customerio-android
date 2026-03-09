package io.customer.location.provider

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import io.customer.location.type.AuthorizationStatus
import io.customer.location.type.LocationGranularity
import io.customer.location.type.LocationProviderError
import io.customer.location.type.LocationSnapshot
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [LocationProvider] implementation wrapping Google's [FusedLocationProviderClient].
 *
 * Uses [FusedLocationProviderClient.getCurrentLocation] for one-shot location requests
 * with cancellation support. Does not request permissions - the host app is responsible.
 */
internal class FusedLocationProvider(
    private val context: Context
) : LocationProvider {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Suppress("MissingPermission")
    override suspend fun requestLocation(granularity: LocationGranularity): LocationSnapshot {
        val authStatus = currentAuthorizationStatus()
        if (!authStatus.isAuthorized) {
            throw LocationRequestException(error = LocationProviderError.PERMISSION_DENIED)
        }

        if (!isLocationServicesEnabled()) {
            throw LocationRequestException(error = LocationProviderError.SERVICES_DISABLED)
        }

        val priority = mapGranularityToPriority(granularity)
        val tokenSource = CancellationTokenSource()

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                tokenSource.cancel()
            }

            fusedClient.getCurrentLocation(priority, tokenSource.token)
                .addOnSuccessListener { location ->
                    if (!continuation.isActive) return@addOnSuccessListener
                    if (location != null) {
                        continuation.resume(
                            LocationSnapshot(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                timestamp = Date(location.time),
                                horizontalAccuracy = location.accuracy.toDouble(),
                                altitude = if (location.hasAltitude()) location.altitude else null
                            )
                        )
                    } else {
                        continuation.resumeWithException(
                            LocationRequestException(error = LocationProviderError.TIMEOUT)
                        )
                    }
                }
                .addOnFailureListener { exception ->
                    if (!continuation.isActive) return@addOnFailureListener
                    continuation.resumeWithException(
                        LocationRequestException(
                            error = LocationProviderError.TIMEOUT,
                            cause = exception
                        )
                    )
                }
                .addOnCanceledListener {
                    if (!continuation.isActive) return@addOnCanceledListener
                    continuation.cancel()
                }
        }
    }

    override suspend fun currentAuthorizationStatus(): AuthorizationStatus {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            return AuthorizationStatus.DENIED
        }

        // Check for background location on Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackground = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasBackground) {
                return AuthorizationStatus.AUTHORIZED_BACKGROUND
            }
        }

        return AuthorizationStatus.AUTHORIZED_FOREGROUND
    }

    private fun isLocationServicesEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun mapGranularityToPriority(granularity: LocationGranularity): Int {
        return when (granularity) {
            LocationGranularity.COARSE_CITY_OR_TIMEZONE -> Priority.PRIORITY_LOW_POWER
        }
    }
}
