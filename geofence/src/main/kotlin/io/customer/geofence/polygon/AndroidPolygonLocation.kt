package io.customer.geofence.polygon

import android.location.Location

internal data class AndroidPolygonLocationFix(
    val sample: PolygonLocationSample,
    val elapsedRealtimeNanos: Long,
    val timestampMillis: Long
)

internal fun Location.toPolygonLocationFix(): AndroidPolygonLocationFix? {
    if (!hasAccuracy() || !accuracy.isFinite() || accuracy <= 0f || elapsedRealtimeNanos <= 0L) return null
    // The range check belongs to this path, not to PolygonCoordinate: a backend payload is the
    // backend's to validate, but a provider can report a position the earth does not have, and
    // carrying one into the evaluator would judge containment against a point that cannot exist.
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
