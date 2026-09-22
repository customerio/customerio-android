package io.customer.geofence.polygon

import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceRegion
import kotlin.math.min

/** Computes one conservative movement-trigger radius across the relevant polygon set. */
internal class PolygonMovementTriggerPolicy {
    fun safeRadiusMeters(
        regions: List<GeofenceRegion>,
        committedInsideIds: Set<String>,
        sample: PolygonLocationSample,
        normalRadiusMeters: Float
    ): Float? {
        if (sample.horizontalAccuracyMeters > MAX_TRIGGER_FIX_ACCURACY_METERS) return null
        val polygons = regions.filter(GeofenceRegion::isPolygon)
        if (polygons.isEmpty()) return normalRadiusMeters

        // Each polygon we are outside imposes a ceiling: the trigger must be crossed before its
        // ring is. Each polygon we are inside wants a small radius so departure is noticed. They
        // are different constraints and must not be collapsed into one running minimum, which is
        // what let a single already-entered polygon floor the trigger for every polygon still being
        // approached.
        var approachCeiling = normalRadiusMeters.toDouble()
        var departureCeiling = Double.MAX_VALUE
        var approaching = false
        var departing = false
        for (region in polygons) {
            val geometry = region.polygonGeometryOrNull() ?: return null
            val relation = geometry.relationTo(sample.coordinate)
            val expectedInside = region.id in committedInsideIds
            val stateMatches = when (relation) {
                PolygonPointRelation.INSIDE -> expectedInside
                PolygonPointRelation.OUTSIDE -> !expectedInside
                PolygonPointRelation.BOUNDARY -> false
            }
            if (!stateMatches) return null

            val clearance = geometry.boundaryDistanceMeters(sample.coordinate) -
                sample.horizontalAccuracyMeters -
                APPROACH_LEAD_MARGIN_METERS
            if (expectedInside) {
                departing = true
                departureCeiling = min(departureCeiling, clearance)
            } else {
                approaching = true
                approachCeiling = min(approachCeiling, clearance)
            }
        }

        // A clearance too small to keep the trigger inside the nearest ring is a refusal, and it
        // stays a refusal even when some other polygon is being departed. Stopping would lose that
        // arrival, and being inside an unrelated fence does not make it recoverable.
        if (approaching && approachCeiling < GeofenceConstants.MIN_LOCAL_REFRESH_RADIUS_METERS) {
            return null
        }
        if (!departing) return approachCeiling.toFloat()

        // Departing, the departed ring's own clearance still applies: 2 km inside a large polygon,
        // a trigger just under 2 km is crossed before the boundary is, and the floor would replace
        // it with 250 m and four to seven times the wakes. Only a ring too small to hold any
        // resolvable trigger falls to the floor, which is every retail fence and not much else.
        //
        // The floor is applied last so it cannot be capped away: a configured refresh radius below
        // it says what the workspace wants, not what GMS delivers, and a trigger under what GMS
        // resolves produces more wakes than the config asked for, not fewer.
        val departureRadius = departureCeiling
            .coerceAtMost(normalRadiusMeters.toDouble())
            .coerceAtLeast(MIN_DEPARTURE_TRIGGER_RADIUS_METERS)

        // An approach in the same set still caps it: widening past a ring being walked towards
        // loses that arrival, which is worse than noticing a departure late.
        return if (approaching) {
            min(approachCeiling, departureRadius).toFloat()
        } else {
            departureRadius.toFloat()
        }
    }

    internal companion object {
        // Draft policy values only. Field calibration freezes these before polygon rollout.
        //
        // Distinct from the evaluator's own accuracy ceilings despite the shared units: this one
        // decides whether a fix is good enough to shrink the movement trigger around a polygon,
        // which is a worse failure than misjudging one fence. It equals the evaluator's decisive
        // ceiling today by coincidence, not by derivation, so calibration may move either alone.
        const val MAX_TRIGGER_FIX_ACCURACY_METERS = 50.0

        /** Lead distance kept between the trigger and the ring on approach. */
        const val APPROACH_LEAD_MARGIN_METERS = 100.0

        /**
         * Smallest trigger armed for a departure, and deliberately not
         * [GeofenceConstants.MIN_LOCAL_REFRESH_RADIUS_METERS] — that clamps server config and says
         * nothing about what GMS can resolve. Coarse containment is wrong by hundreds of metres, and
         * a trigger that fires on that error costs a sync and a re-registration each time. 250 m is
         * the smallest radius anything here has field-validated. v1 value; the drive can measure the
         * wake rate at it.
         */
        const val MIN_DEPARTURE_TRIGGER_RADIUS_METERS = 250.0
    }
}
