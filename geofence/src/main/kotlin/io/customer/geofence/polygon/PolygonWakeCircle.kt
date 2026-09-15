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
 * The registered radius is floored, not padded. A coarse GMS containment decision is wrong by
 * hundreds of metres in the field — a 500 m fence was marked INSIDE at 889 m from its centre on the
 * 2026-07-31 drive — so a ring must stay large enough that this error cannot drive it. Polygons are
 * excluded from initial-ENTER synthesis, which makes a missed coarse ENTER unrecoverable, while a
 * spurious one only costs a sampling session. The floor keeps that asymmetry on the safe side.
 *
 * Flooring rather than adding is the original two-part policy: the spike carried both a pad and a
 * `MINIMUM_TRIGGER_RADIUS_METERS`, and only the pad survived the re-splits. Adding a kilometre to a
 * circle that is already kilometres wide buys nothing, so a large polygon is now registered as the
 * backend describes it.
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
