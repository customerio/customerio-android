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

        // Each polygon we are outside caps the trigger so it is crossed before the ring; each one
        // we are inside wants a small radius so departure is noticed. Kept separate: one running
        // minimum would let a single entered polygon floor the trigger for every approach.
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

        // The departed ring's own clearance still applies, so deep inside a large polygon the
        // trigger stays large; only a ring too small for a resolvable trigger falls to the floor.
        // The floor is applied last so a smaller configured radius cannot cap it below what GMS
        // resolves.
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
        // Independent of the evaluator's arrival ceiling: a bad fix here shrinks the movement
        // trigger around every polygon, a worse failure than misjudging one fence. Equal to the
        // evaluator's floor by coincidence, so either may change alone.
        const val MAX_TRIGGER_FIX_ACCURACY_METERS = 50.0

        /** Lead distance kept between the trigger and the ring on approach. */
        const val APPROACH_LEAD_MARGIN_METERS = 100.0

        /**
         * Smallest trigger armed for a departure. Not
         * [GeofenceConstants.MIN_LOCAL_REFRESH_RADIUS_METERS], which clamps server config and says
         * nothing about what GMS resolves. A trigger smaller than GMS's coarse containment error
         * fires falsely, and each false fire costs a sync and a re-registration.
         */
        const val MIN_DEPARTURE_TRIGGER_RADIUS_METERS = 250.0
    }
}
