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
 * 400 m is a calibration step down from the 1000 m this carried until 2026-09-17, not a derived
 * value. Two things moved it.
 *
 * The old floor's headline justification was a measured GMS coarse-containment error of 889 m on
 * a 500 m fence. It does not apply to a polygon: both containment paths filter `!isPolygon`, so
 * that error never reaches a polygon's registered radius. It only ever justified the floor for
 * circles, which do not read this value.
 *
 * The approach session is capped at two minutes. The wake fires when the circle is crossed, so a
 * radius over roughly `speed * cap` expires the session before arrival: at 1000 m and a measured
 * 26 km/h that is 139 s of approach against a 120 s budget, and the arrival is never evaluated
 * even when GMS delivers perfectly. 400 m leaves about four samples inside the budget at that
 * speed. A fixed radius and a fixed cap cannot serve walking and driving at once, which is the
 * real defect and is not fixed here.
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
