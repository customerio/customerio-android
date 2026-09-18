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
 * admission and the canonical base circle. What remains here is a platform bound on the radius.
 *
 * **The circle is registered exactly as sent.** It already encloses the ring, so padding it only
 * moves the wake further from the venue. On the 2026-09-18 Rainbow visit the padded 400 m circle
 * woke us 193 m outside a shop whose own circle is 101 m; iOS registers that 101 m verbatim and
 * woke 1 m inside the ring on the same trip, and recorded the visit we missed.
 *
 * The floor this replaces was argued from two things, and neither survived.
 *
 * A measured GMS coarse-containment error of 889 m on a 500 m fence. That number reached the floor
 * through SDK-side containment, and both containment paths filter `!isPolygon`, so it never
 * applied to a polygon by that route.
 *
 * And the approach-session admission gate, which needed the whole accuracy circle inside the
 * radius. That gate feeds interval sampling, which is not serviced for a background app at all
 * (measured 2026-09-18: 3 batches in 120 s foreground, 1 batch in 44 minutes in the field, same
 * build). So the padding was protecting a mechanism that cannot run in the regime it was padded
 * for.
 *
 * **One tension is unresolved and the next capture is what resolves it.** The 889 m datum no longer
 * justifies the floor, but it is still evidence that GMS's own position estimate can be wrong by
 * hundreds of metres, and a wake circle depends entirely on GMS callbacks. Nothing below 250 m has
 * been field-checked in this repository and the backend's circles run from about 100 m upward, so
 * whether a 100 m circle fires reliably is not known here. The asymmetry that makes it matter is
 * live: polygons are excluded from initial-ENTER synthesis (`!isPolygon` in
 * `GeofenceRepository.registerNearestAndPersist` and `reconcileContainment`), so a coarse ENTER
 * that never fires is unrecoverable.
 *
 * That is a reason to read the next capture for which radius registered and whether it fired, not a
 * reason to pad a circle that is already larger than the shape inside it, and not a reason to floor
 * at 250 m either: picking the smallest validated number to cover an unknown is what the 400 m was.
 */
internal class PolygonWakeCircleValidator {
    fun prepare(wakeCircle: PolygonWakeCircle): PolygonTriggerCircle {
        require(wakeCircle.baseRadiusMeters.isFinite() && wakeCircle.baseRadiusMeters > 0.0) {
            "polygon wake-circle radius must be finite and positive"
        }
        require(wakeCircle.baseRadiusMeters <= MAXIMUM_TRIGGER_RADIUS_METERS) {
            "polygon wake circle exceeds the maximum supported radius"
        }
        return PolygonTriggerCircle(
            center = wakeCircle.center,
            radiusMeters = wakeCircle.baseRadiusMeters.toFloat()
        )
    }

    fun prepareOrNull(wakeCircle: PolygonWakeCircle): PolygonTriggerCircle? = try {
        prepare(wakeCircle)
    } catch (_: IllegalArgumentException) {
        null
    }

    internal companion object {
        const val MAXIMUM_TRIGGER_RADIUS_METERS = 100_000.0
    }
}
