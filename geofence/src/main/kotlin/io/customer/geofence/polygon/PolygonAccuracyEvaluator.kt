package io.customer.geofence.polygon

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal data class PolygonLocationSample(
    val coordinate: PolygonCoordinate,
    val horizontalAccuracyMeters: Double
) {
    init {
        require(horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters >= 0.0) {
            "horizontal accuracy must be finite and non-negative"
        }
    }
}

internal enum class PolygonCommittedState {
    OUTSIDE,
    INSIDE
}

internal enum class PolygonEvidence {
    ENTER,
    EXIT,
    AMBIGUOUS
}

/** Classifies a location fix without mutating committed polygon state. */
internal class PolygonAccuracyEvaluator {
    fun evidenceFor(
        geometry: PolygonGeometry,
        sample: PolygonLocationSample,
        committedState: PolygonCommittedState
    ): PolygonEvidence {
        if (sample.horizontalAccuracyMeters > MAX_EVALUATED_FIX_ACCURACY_METERS) {
            return PolygonEvidence.AMBIGUOUS
        }
        val centerRelation = geometry.relationTo(sample.coordinate)
        if (centerRelation == PolygonPointRelation.BOUNDARY) return PolygonEvidence.AMBIGUOUS

        val accuracy = when (committedState) {
            PolygonCommittedState.OUTSIDE -> sample.horizontalAccuracyMeters
            PolygonCommittedState.INSIDE -> max(
                sample.horizontalAccuracyMeters,
                min(
                    sample.horizontalAccuracyMeters + EXIT_ACCURACY_INFLATION_METERS,
                    MAX_EXIT_ACCURACY_METERS
                )
            )
        }
        val perimeterRelations = perimeterSamples(sample.coordinate, accuracy)
            .map(geometry::relationTo)
        val insideCount = perimeterRelations.count { it == PolygonPointRelation.INSIDE }
        val confidence = insideCount.toDouble() / PERIMETER_SAMPLE_COUNT

        return when {
            centerRelation == PolygonPointRelation.INSIDE &&
                confidence >= ENTER_CONFIDENCE_THRESHOLD -> PolygonEvidence.ENTER
            centerRelation == PolygonPointRelation.OUTSIDE &&
                geometry.boundaryDistanceMeters(sample.coordinate) > accuracy ->
                PolygonEvidence.EXIT
            else -> PolygonEvidence.AMBIGUOUS
        }
    }

    /**
     * Classifies a sparse background fix only when its complete accuracy circle, plus an
     * additional anti-jitter margin, is on the candidate side of the polygon boundary.
     */
    fun decisiveEvidenceFor(
        geometry: PolygonGeometry,
        sample: PolygonLocationSample,
        committedState: PolygonCommittedState
    ): PolygonEvidence {
        if (sample.horizontalAccuracyMeters > MAX_DECISIVE_FIX_ACCURACY_METERS) {
            return PolygonEvidence.AMBIGUOUS
        }
        val relation = geometry.relationTo(sample.coordinate)
        if (relation == PolygonPointRelation.BOUNDARY) return PolygonEvidence.AMBIGUOUS
        val requiredDistance = sample.horizontalAccuracyMeters + DECISIVE_BOUNDARY_MARGIN_METERS
        if (geometry.boundaryDistanceMeters(sample.coordinate) <= requiredDistance) {
            return PolygonEvidence.AMBIGUOUS
        }
        return when {
            committedState == PolygonCommittedState.OUTSIDE && relation == PolygonPointRelation.INSIDE ->
                PolygonEvidence.ENTER
            committedState == PolygonCommittedState.INSIDE && relation == PolygonPointRelation.OUTSIDE ->
                PolygonEvidence.EXIT
            else -> PolygonEvidence.AMBIGUOUS
        }
    }

    private fun perimeterSamples(
        center: PolygonCoordinate,
        radiusMeters: Double
    ): List<PolygonCoordinate> = List(PERIMETER_SAMPLE_COUNT) { index ->
        val bearing = 2.0 * PI * index / PERIMETER_SAMPLE_COUNT
        destination(center, bearing, radiusMeters)
    }

    private fun destination(
        origin: PolygonCoordinate,
        bearingRadians: Double,
        distanceMeters: Double
    ): PolygonCoordinate {
        if (distanceMeters == 0.0) return origin

        val latitude = Math.toRadians(origin.latitude)
        val longitude = Math.toRadians(origin.longitude)
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val destinationLatitude = asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearingRadians)
        )
        val destinationLongitude = longitude + atan2(
            sin(bearingRadians) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(destinationLatitude)
        )

        return PolygonCoordinate(
            latitude = Math.toDegrees(destinationLatitude),
            longitude = normalizeLongitude(Math.toDegrees(destinationLongitude))
        )
    }

    private fun normalizeLongitude(longitude: Double): Double =
        ((longitude + 540.0) % 360.0) - 180.0

    private companion object {
        /**
         * Fixed, not configurable. These were constructor defaults on a config object that nothing
         * ever built with other values, production or test, so the bounds it checked only ever
         * validated the defaults against themselves. Calibration changes them here.
         */
        const val PERIMETER_SAMPLE_COUNT = 16
        const val ENTER_CONFIDENCE_THRESHOLD = 0.4
        const val EXIT_ACCURACY_INFLATION_METERS = 2.0
        const val MAX_EXIT_ACCURACY_METERS = 50.0

        /** Coarser than this and a fix cannot judge containment at all, so it reads AMBIGUOUS. */
        const val MAX_EVALUATED_FIX_ACCURACY_METERS = 200.0

        /** The stricter ceiling a lone background fix must meet to decide without corroboration. */
        const val MAX_DECISIVE_FIX_ACCURACY_METERS = 50.0
        const val DECISIVE_BOUNDARY_MARGIN_METERS = 10.0

        const val EARTH_RADIUS_METERS = PolygonGeometry.EARTH_RADIUS_METERS
    }
}
