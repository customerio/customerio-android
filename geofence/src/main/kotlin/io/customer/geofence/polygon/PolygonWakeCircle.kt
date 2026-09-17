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
 * One constraint sets the floor, from this repository's own field data: activation reads this
 * radius, and a polygon is admitted only when `distance + accuracy <= radius`. Indoor fixes
 * measure around 100 m, so a ring near the backend's 101 m retail circle cannot admit the polygon
 * at all, from any position. At 400 m that admits from 300 m out on an indoor fix.
 *
 * Getting it wrong is asymmetric. Polygons are excluded from initial-ENTER synthesis, so a missed
 * coarse ENTER is unrecoverable, while a spurious one costs a sampling session.
 *
 * 400 m is a calibration hypothesis to be tested on the next drive, not a derived value and not a
 * demonstration that 1000 m cannot work. Two observations motivate trying it.
 *
 * The old floor's headline justification was a measured GMS coarse-containment error of 889 m on a
 * 500 m fence. That number reached the floor through SDK-side containment, and both containment
 * paths filter `!isPolygon`, so it never justified the floor for a polygon by that route. It is
 * still evidence about how far GMS's own position estimate can be wrong, and a wake circle depends
 * entirely on GMS callbacks, so it argues *against* shrinking as much as the old comment argued
 * for it. That tension is unresolved and the drive is what resolves it.
 *
 * The approach session is capped at two minutes and the wake fires when the circle is crossed, so
 * at 1000 m and a measured 26 km/h the crossing is 139 s from arrival against a 120 s budget. That
 * is one window, not the whole story: a movement-trigger handoff stops sampling and a later
 * crossing can open a fresh session, so the arrival is not necessarily lost. 400 m puts the whole
 * approach inside a single window at that speed, which makes the next capture far easier to read.
 *
 * A fixed radius against a fixed cap cannot serve walking and driving at once. That is a real
 * defect, it is not fixed here, and it is not what this constant is trying to solve.
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
        const val MINIMUM_TRIGGER_RADIUS_METERS = 400.0
        const val MAXIMUM_TRIGGER_RADIUS_METERS = 100_000.0
    }
}
