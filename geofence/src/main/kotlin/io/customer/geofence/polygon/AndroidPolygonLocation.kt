package io.customer.geofence.polygon

import android.location.Location

internal data class AndroidPolygonLocationFix(
    val sample: PolygonLocationSample,
    val elapsedRealtimeNanos: Long
)

internal fun Location.toPolygonLocationFix(): AndroidPolygonLocationFix? {
    if (!hasAccuracy() || !accuracy.isFinite() || accuracy <= 0f || elapsedRealtimeNanos <= 0L) return null

    return try {
        AndroidPolygonLocationFix(
            sample = PolygonLocationSample(
                coordinate = PolygonCoordinate(latitude, longitude),
                horizontalAccuracyMeters = accuracy.toDouble()
            ),
            elapsedRealtimeNanos = elapsedRealtimeNanos
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}
