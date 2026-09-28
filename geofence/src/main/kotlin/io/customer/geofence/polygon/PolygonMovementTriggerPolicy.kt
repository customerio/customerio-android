package io.customer.geofence.polygon

import io.customer.geofence.GeofenceConstants
import io.customer.geofence.GeofenceRegion
import kotlin.math.min

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

        // Approach and departure ceilings kept apart: one running minimum would let a single
        // entered polygon floor the trigger for every approach.
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

        // Too little clearance before the nearest ring is a refusal even while another polygon is
        // departed: stopping would lose that arrival.
        if (approaching && approachCeiling < GeofenceConstants.MIN_LOCAL_REFRESH_RADIUS_METERS) {
            return null
        }
        if (!departing) return approachCeiling.toFloat()

        // Floor applied last so a smaller configured radius cannot cap it below what GMS resolves.
        val departureRadius = departureCeiling
            .coerceAtMost(normalRadiusMeters.toDouble())
            .coerceAtLeast(MIN_DEPARTURE_TRIGGER_RADIUS_METERS)

        // An approach still caps it: a lost arrival is worse than a late departure.
        return if (approaching) {
            min(approachCeiling, departureRadius).toFloat()
        } else {
            departureRadius.toFloat()
        }
    }

    internal companion object {
        // Equals the evaluator's arrival floor only by coincidence: a bad fix here shrinks the
        // trigger around every polygon, so either may change alone.
        const val MAX_TRIGGER_FIX_ACCURACY_METERS = 50.0

        const val APPROACH_LEAD_MARGIN_METERS = 100.0

        /**
         * Not [GeofenceConstants.MIN_LOCAL_REFRESH_RADIUS_METERS], which clamps server config. A
         * trigger smaller than GMS's coarse containment error fires falsely, each costing a sync.
         */
        const val MIN_DEPARTURE_TRIGGER_RADIUS_METERS = 250.0
    }
}
