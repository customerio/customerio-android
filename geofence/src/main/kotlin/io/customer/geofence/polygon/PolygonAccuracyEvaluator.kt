package io.customer.geofence.polygon

import kotlin.math.max

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
 * Left unset for a decisive fix that agrees with the committed state, or a still device would log
 * per fix.
 */
internal enum class PolygonUndecidedReason(val wire: String) {
    ACCURACY_TOO_LOW("accuracy_too_low"),
    ON_BOUNDARY("on_boundary"),
    WITHIN_ACCURACY("within_accuracy")
}

internal data class PolygonEvidenceResult(
    val evidence: PolygonEvidence,
    val undecidedReason: PolygonUndecidedReason? = null,
    /** Positive inside, negative outside. Null only where the geometry was never consulted. */
    val signedBoundaryDistanceMeters: Double? = null,
    /** The fix's own uncertainty reaches the ring, so a second agreeing fix must settle it. */
    val requiresCorroboration: Boolean = false,
    /** Decisive and on the committed side: no transition, but still recorded. */
    val agreedWithCommittedState: Boolean = false
)

internal class PolygonAccuracyEvaluator {
    /**
     * Asymmetric: a missed arrival loses the visit (polygons get no synthesized initial ENTER), so
     * arrival needs only the fix inside the ring while departure also needs a clearance margin. A
     * symmetric margin would leave no decidable point in a ring shallower than accuracy plus margin.
     */
    fun decisiveEvidenceFor(
        geometry: PolygonGeometry,
        sample: PolygonLocationSample,
        committedState: PolygonCommittedState
    ): PolygonEvidenceResult {
        val relation = geometry.relationTo(sample.coordinate)
        if (relation == PolygonPointRelation.BOUNDARY) {
            return undecided(
                PolygonUndecidedReason.ON_BOUNDARY,
                geometry.signedBoundaryDistanceMeters(sample.coordinate)
            )
        }
        val boundaryDistanceMeters = geometry.boundaryDistanceMeters(sample.coordinate)
        val signedBoundaryDistanceMeters = signedBoundaryDistance(boundaryDistanceMeters, relation)
        // Before the accuracy ceiling: a fix this clear has settled the side however coarse, and
        // dropping it would leave the visit open so the redundant-ENTER guard suppresses the next
        // arrival.
        if (
            relation == PolygonPointRelation.OUTSIDE &&
            boundaryDistanceMeters > sample.horizontalAccuracyMeters + DEPARTURE_BOUNDARY_MARGIN_METERS
        ) {
            return if (committedState == PolygonCommittedState.INSIDE) {
                PolygonEvidenceResult(
                    PolygonEvidence.EXIT,
                    signedBoundaryDistanceMeters = signedBoundaryDistanceMeters
                )
            } else {
                PolygonEvidenceResult(
                    PolygonEvidence.AMBIGUOUS,
                    signedBoundaryDistanceMeters = signedBoundaryDistanceMeters,
                    agreedWithCommittedState = true
                )
            }
        }
        // Governs arrivals only. Scales with venue depth so a coarse fix deep in a large venue
        // counts, floored so a shallow ring still admits a coarse fix.
        val arrivalCeilingMeters = max(MINIMUM_ARRIVAL_CEILING_METERS, geometry.venueScaleMeters)
        if (sample.horizontalAccuracyMeters >= arrivalCeilingMeters) {
            return undecided(
                PolygonUndecidedReason.ACCURACY_TOO_LOW,
                signedBoundaryDistanceMeters
            )
        }
        return when {
            committedState == PolygonCommittedState.OUTSIDE && relation == PolygonPointRelation.INSIDE ->
                PolygonEvidenceResult(
                    PolygonEvidence.ENTER,
                    signedBoundaryDistanceMeters = signedBoundaryDistanceMeters,
                    // Arrival has no clearance margin, so this is its only accuracy bound.
                    requiresCorroboration =
                    boundaryDistanceMeters <= sample.horizontalAccuracyMeters
                )
            committedState == PolygonCommittedState.INSIDE && relation == PolygonPointRelation.OUTSIDE ->
                undecided(PolygonUndecidedReason.WITHIN_ACCURACY, signedBoundaryDistanceMeters)
            else -> PolygonEvidenceResult(
                PolygonEvidence.AMBIGUOUS,
                signedBoundaryDistanceMeters = signedBoundaryDistanceMeters,
                agreedWithCommittedState = true
            )
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

    private fun PolygonGeometry.signedBoundaryDistanceMeters(point: PolygonCoordinate): Double =
        signedBoundaryDistance(boundaryDistanceMeters(point), relationTo(point))

    private fun signedBoundaryDistance(
        boundaryDistanceMeters: Double,
        relation: PolygonPointRelation
    ): Double =
        if (relation == PolygonPointRelation.OUTSIDE) -boundaryDistanceMeters else boundaryDistanceMeters

    private companion object {
        /** Looser than iOS, whose arrival ceiling is the venue depth alone. */
        const val MINIMUM_ARRIVAL_CEILING_METERS = 50.0

        const val DEPARTURE_BOUNDARY_MARGIN_METERS = 20.0
    }
}
