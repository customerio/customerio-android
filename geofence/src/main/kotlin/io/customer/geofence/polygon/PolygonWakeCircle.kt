package io.customer.geofence.polygon

internal data class PolygonWakeCircle(
    val center: PolygonCoordinate,
    val baseRadiusMeters: Double
)

internal data class PolygonTriggerCircle(
    val center: PolygonCoordinate,
    val radiusMeters: Float
)

/**
 * Pads the canonical wake circle supplied by the backend.
 *
 * The SDK derives no enclosing circle and re-checks no coverage: the backend owns geometry
 * admission and the canonical base circle. What remains here is what the platform needs to register
 * the trigger — a usable radius, padded by the wake margin and inside the OS limit.
 */
internal class PolygonWakeCircleValidator {
    fun prepare(wakeCircle: PolygonWakeCircle): PolygonTriggerCircle {
        require(wakeCircle.baseRadiusMeters.isFinite() && wakeCircle.baseRadiusMeters > 0.0) {
            "polygon wake-circle radius must be finite and positive"
        }
        val registeredRadius = wakeCircle.baseRadiusMeters + PLATFORM_WAKE_MARGIN_METERS
        require(registeredRadius <= MAXIMUM_TRIGGER_RADIUS_METERS) {
            "polygon wake circle exceeds the maximum supported radius"
        }
        return PolygonTriggerCircle(
            center = wakeCircle.center,
            radiusMeters = registeredRadius.toFloat()
        )
    }

    fun prepareOrNull(wakeCircle: PolygonWakeCircle): PolygonTriggerCircle? = try {
        prepare(wakeCircle)
    } catch (_: IllegalArgumentException) {
        null
    }

    internal companion object {
        // Prototype policy value. Field calibration freezes the versioned production margin.
        const val PLATFORM_WAKE_MARGIN_METERS = 1_000.0
        const val MAXIMUM_TRIGGER_RADIUS_METERS = 100_000.0
    }
}
