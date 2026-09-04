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
        if (sample.horizontalAccuracyMeters > MAXIMUM_ACCEPTED_ACCURACY_METERS) return null
        val polygons = regions.filter(GeofenceRegion::isPolygon)
        if (polygons.isEmpty()) return normalRadiusMeters

        var safeRadius = normalRadiusMeters.toDouble()
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
                PROTOTYPE_WAKE_POLICY_MARGIN_METERS
            safeRadius = min(safeRadius, clearance)
        }

        return safeRadius
            .takeIf { it >= GeofenceConstants.MIN_LOCAL_REFRESH_RADIUS_METERS }
            ?.toFloat()
    }

    internal companion object {
        // Draft policy values only. Field calibration freezes these before polygon rollout.
        const val MAXIMUM_ACCEPTED_ACCURACY_METERS = 50.0
        const val PROTOTYPE_WAKE_POLICY_MARGIN_METERS = 100.0
    }
}
