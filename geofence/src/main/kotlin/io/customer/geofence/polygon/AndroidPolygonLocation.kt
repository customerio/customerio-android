package io.customer.geofence.polygon

import android.location.Location

internal data class AndroidPolygonLocationFix(
    val sample: PolygonLocationSample,
    val elapsedRealtimeNanos: Long,
    val timestampMillis: Long
)

internal fun Location.toPolygonLocationFix(): AndroidPolygonLocationFix? {
    if (!hasAccuracy() || !accuracy.isFinite() || accuracy <= 0f || elapsedRealtimeNanos <= 0L) return null
    // Range-checked here, not in PolygonCoordinate: a provider can report an impossible position,
    // while backend payloads are the backend's to validate.
    if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
    if (!longitude.isFinite() || longitude !in -180.0..180.0) return null

    return AndroidPolygonLocationFix(
        sample = PolygonLocationSample(
            coordinate = PolygonCoordinate(latitude, longitude),
            horizontalAccuracyMeters = accuracy.toDouble()
        ),
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        timestampMillis = time
    )
}
