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

/**
 * Why a fix could not be classified. Only set when the fix was genuinely undecidable: a decisive
 * fix that agrees with the committed state is not undecided, and reporting it would emit a record
 * per fix for a device sitting still inside a polygon.
 */
internal enum class PolygonUndecidedReason(val wire: String) {
    ACCURACY_TOO_LOW("accuracy_too_low"),
    ON_BOUNDARY("on_boundary"),
    WITHIN_ACCURACY("within_accuracy")
}

internal data class PolygonEvidenceResult(
    val evidence: PolygonEvidence,
    val undecidedReason: PolygonUndecidedReason? = null,
    /** Positive inside, negative outside, as iOS reports it. Null unless [undecidedReason] is set. */
    val signedBoundaryDistanceMeters: Double? = null
)

/** Classifies a location fix without mutating committed polygon state. */
internal class PolygonAccuracyEvaluator {
    fun evidenceFor(
        geometry: PolygonGeometry,
        sample: PolygonLocationSample,
        committedState: PolygonCommittedState
    ): PolygonEvidenceResult {
        if (sample.horizontalAccuracyMeters > MAX_EVALUATED_FIX_ACCURACY_METERS) {
            return undecided(
                PolygonUndecidedReason.ACCURACY_TOO_LOW,
                geometry.signedBoundaryDistanceMeters(sample.coordinate)
            )
        }
        val centerRelation = geometry.relationTo(sample.coordinate)
        if (centerRelation == PolygonPointRelation.BOUNDARY) {
            return undecided(
                PolygonUndecidedReason.ON_BOUNDARY,
                geometry.signedBoundaryDistanceMeters(sample.coordinate)
            )
        }

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

        val boundaryDistanceMeters = geometry.boundaryDistanceMeters(sample.coordinate)
        return when {
            centerRelation == PolygonPointRelation.INSIDE &&
                confidence >= ENTER_CONFIDENCE_THRESHOLD ->
                PolygonEvidenceResult(PolygonEvidence.ENTER)
            centerRelation == PolygonPointRelation.OUTSIDE && boundaryDistanceMeters > accuracy ->
                PolygonEvidenceResult(PolygonEvidence.EXIT)
            else -> undecided(
                PolygonUndecidedReason.WITHIN_ACCURACY,
                signedBoundaryDistance(boundaryDistanceMeters, centerRelation)
            )
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
    ): PolygonEvidenceResult {
        if (sample.horizontalAccuracyMeters > MAX_DECISIVE_FIX_ACCURACY_METERS) {
            return undecided(
                PolygonUndecidedReason.ACCURACY_TOO_LOW,
                geometry.signedBoundaryDistanceMeters(sample.coordinate)
            )
        }
        val relation = geometry.relationTo(sample.coordinate)
        if (relation == PolygonPointRelation.BOUNDARY) {
            return undecided(
                PolygonUndecidedReason.ON_BOUNDARY,
                geometry.signedBoundaryDistanceMeters(sample.coordinate)
            )
        }
        val boundaryDistanceMeters = geometry.boundaryDistanceMeters(sample.coordinate)
        val requiredDistance = sample.horizontalAccuracyMeters + DECISIVE_BOUNDARY_MARGIN_METERS
        if (boundaryDistanceMeters <= requiredDistance) {
            return undecided(
                PolygonUndecidedReason.WITHIN_ACCURACY,
                signedBoundaryDistance(boundaryDistanceMeters, relation)
            )
        }
        return when {
            committedState == PolygonCommittedState.OUTSIDE && relation == PolygonPointRelation.INSIDE ->
                PolygonEvidenceResult(PolygonEvidence.ENTER)
            committedState == PolygonCommittedState.INSIDE && relation == PolygonPointRelation.OUTSIDE ->
                PolygonEvidenceResult(PolygonEvidence.EXIT)
            // Decisive, and it agrees with the committed state. Nothing to decide, so no reason and
            // no record: this is what a device sitting inside a polygon reports on every fix.
            else -> PolygonEvidenceResult(PolygonEvidence.AMBIGUOUS)
        }
    }

    private fun undecided(
        reason: PolygonUndecidedReason,
        signedBoundaryDistanceMeters: Double
    ) = PolygonEvidenceResult(
        evidence = PolygonEvidence.AMBIGUOUS,
        undecidedReason = reason,
        signedBoundaryDistanceMeters = signedBoundaryDistanceMeters
    )

    /**
     * The decisions above all compare an unsigned distance, so the sign is only ever attached on
     * the way out to the record. Unsigned, a row cannot tell a fix refused 26 m inside the polygon
     * from one refused 26 m outside it, which is the question the margins are calibrated against.
     */
    private fun PolygonGeometry.signedBoundaryDistanceMeters(point: PolygonCoordinate): Double =
        signedBoundaryDistance(boundaryDistanceMeters(point), relationTo(point))

    private fun signedBoundaryDistance(
        boundaryDistanceMeters: Double,
        relation: PolygonPointRelation
    ): Double =
        if (relation == PolygonPointRelation.OUTSIDE) -boundaryDistanceMeters else boundaryDistanceMeters

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
