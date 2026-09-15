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
 * Validates the canonical wake circle supplied by the backend.
 *
 * The SDK derives no enclosing circle and re-checks no coverage: the backend owns geometry
 * admission and the canonical base circle. What remains here is what the platform needs to register
 * the trigger — a usable radius, inside the OS limit.
 *
 * The registered radius is floored, not padded.
 *
 * Two measured constraints set the floor, both from this repository's own field data:
 *  - GMS coarse containment is wrong by hundreds of metres. On 2026-07-31 a 500 m fence was marked
 *    INSIDE while the device was 889 m from its centre, with a closest approach of 537 m. A ring
 *    smaller than that error is driven by the error rather than by the device.
 *  - Activation reads this radius: a polygon is only admitted when `distance + accuracy <= radius`.
 *    Indoor fixes measure around 100 m, so a ring near the backend's 101 m retail circle cannot
 *    admit the polygon at all, from any position.
 *
 * Getting it wrong is asymmetric. Polygons are excluded from initial-ENTER synthesis, so a missed
 * coarse ENTER is unrecoverable, while a spurious one costs a sampling session.
 *
 * 1000 m is one observed worst-case containment error, rounded. It is the weakest part of this and
 * a drive that records ENTER reliability against a smaller registration would replace it. Adding a
 * kilometre on top of a circle already kilometres wide answers neither constraint, so a large
 * polygon is registered as the backend describes it.
 */
internal class PolygonWakeCircleValidator {
    fun prepare(wakeCircle: PolygonWakeCircle): PolygonTriggerCircle {
        require(wakeCircle.baseRadiusMeters.isFinite() && wakeCircle.baseRadiusMeters > 0.0) {
            "polygon wake-circle radius must be finite and positive"
        }
        val registeredRadius = maxOf(wakeCircle.baseRadiusMeters, MINIMUM_TRIGGER_RADIUS_METERS)
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
        /**
         * Smallest circle handed to GMS. Bounds the coarse containment error described above; it is
         * not a geometry rule, so it never shrinks a circle and never breaks ring containment.
         */
        const val MINIMUM_TRIGGER_RADIUS_METERS = 1_000.0
        const val MAXIMUM_TRIGGER_RADIUS_METERS = 100_000.0
    }
}
