package io.customer.geofence.polygon

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class PolygonWakeCircle(
    val center: PolygonCoordinate,
    val baseRadiusMeters: Double
)

internal data class PolygonTriggerCircle(
    val center: PolygonCoordinate,
    val radiusMeters: Float
)

/**
 * Validates and pads the canonical wake circle supplied by the backend.
 *
 * The SDK deliberately does not derive a new enclosing circle. The backend owns geometry
 * admission and the canonical base circle; Android only checks that the payload is usable and that
 * every admitted vertex is contained before applying the platform wake margin.
 */
internal class PolygonWakeCircleValidator {
    fun prepare(
        geometry: PolygonGeometry,
        wakeCircle: PolygonWakeCircle
    ): PolygonTriggerCircle {
        require(wakeCircle.baseRadiusMeters.isFinite() && wakeCircle.baseRadiusMeters > 0.0) {
            "polygon wake-circle radius must be finite and positive"
        }
        geometry.vertices.forEach { vertex ->
            require(wakeCircle.center.distanceMetersTo(vertex) <= wakeCircle.baseRadiusMeters + CONTAINMENT_TOLERANCE_METERS) {
                "polygon wake circle does not contain every vertex"
            }
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

    fun prepareOrNull(
        geometry: PolygonGeometry,
        wakeCircle: PolygonWakeCircle
    ): PolygonTriggerCircle? = try {
        prepare(geometry, wakeCircle)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun PolygonCoordinate.distanceMetersTo(other: PolygonCoordinate): Double {
        val firstLatitude = Math.toRadians(latitude)
        val secondLatitude = Math.toRadians(other.latitude)
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(other.longitude - longitude)
        val haversine = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
            cos(firstLatitude) * cos(secondLatitude) *
            sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
        val clamped = haversine.coerceIn(0.0, 1.0)
        val centralAngle = 2.0 * atan2(sqrt(clamped), sqrt(1.0 - clamped))
        return MAXIMUM_EARTH_RADIUS_METERS * centralAngle
    }

    internal companion object {
        // Prototype policy value. Field calibration freezes the versioned production margin.
        const val PLATFORM_WAKE_MARGIN_METERS = 1_000.0
        const val MAXIMUM_TRIGGER_RADIUS_METERS = 100_000.0
        const val CONTAINMENT_TOLERANCE_METERS = 1.0
        private const val MAXIMUM_EARTH_RADIUS_METERS = 6_400_000.0
    }
}
