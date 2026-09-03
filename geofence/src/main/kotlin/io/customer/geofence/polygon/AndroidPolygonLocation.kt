package io.customer.geofence.polygon

import android.location.Location

internal data class AndroidPolygonLocationFix(
    val sample: PolygonLocationSample,
    val elapsedRealtimeNanos: Long,
    val timestampMillis: Long
)

internal fun Location.toPolygonLocationFix(): AndroidPolygonLocationFix? {
    if (!hasAccuracy() || !accuracy.isFinite() || accuracy <= 0f || elapsedRealtimeNanos <= 0L) return null

    // fromOrNull, not the constructor: PolygonCoordinate is a wire type and stays passive, so an
    // out-of-range fix is rejected here rather than carried into the evaluator as a real position.
    val coordinate = PolygonCoordinate.fromOrNull(latitude, longitude) ?: return null

    return AndroidPolygonLocationFix(
        sample = PolygonLocationSample(
            coordinate = coordinate,
            horizontalAccuracyMeters = accuracy.toDouble()
        ),
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        timestampMillis = time
    )
}
