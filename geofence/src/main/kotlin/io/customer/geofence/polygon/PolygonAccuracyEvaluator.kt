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
 * Why a fix could not be classified. Not set for a decisive fix that agrees with the committed
 * state, or a device sitting still inside a polygon would emit a record per fix.
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
    /**
     * The fix decided, but its own uncertainty reaches the ring, so it cannot rule out having been
     * taken from the other side. A second agreeing fix settles it.
     */
    val requiresCorroboration: Boolean = false,
    /**
     * Decisive, on the side already committed, so nothing to report. Still recorded so the fixes the
     * margins accepted can be compared with the ones they refused.
     */
    val agreedWithCommittedState: Boolean = false
)

/** Classifies a location fix without mutating committed polygon state. */
internal class PolygonAccuracyEvaluator {
    /**
     * Classifies a sparse background fix, asymmetrically. A missed arrival loses the visit (polygons
     * get no synthesized initial ENTER), while a spurious one is corrected by the next decisive fix;
     * an early departure ends a visit still in progress. So arrival only needs the fix inside the
     * ring, and departure also needs a clearance margin. A symmetric margin would leave no decidable
     * point inside a ring shallower than the fix's accuracy plus that margin.
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
        // Clearance before the accuracy ceiling: a fix clear of the ring by more than its accuracy
        // plus the margin has settled the side however coarse it is. Discarding it would leave the
        // visit open, and the redundant-ENTER guard would then suppress the next arrival.
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
        // From here the ceiling governs arrivals only. It scales with venue depth so a coarse fix
        // well inside a large venue still counts, and has a floor so a shallow ring still admits a
        // coarse fix. A marginal fix opens a hold that any later judged fix
        // reading outside discards.
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
                    // Arrival carries no clearance margin, so this is the only accuracy bound
                    // against the boundary.
                    requiresCorroboration =
                    boundaryDistanceMeters <= sample.horizontalAccuracyMeters
                )
            // Outside, and not clear of the ring by enough to have ruled out the other side.
            committedState == PolygonCommittedState.INSIDE && relation == PolygonPointRelation.OUTSIDE ->
                undecided(PolygonUndecidedReason.WITHIN_ACCURACY, signedBoundaryDistanceMeters)
            // Decisive and agrees with the committed state: no transition, but still recorded.
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

    /** Decisions use unsigned distance; the sign is for the record, to tell inside from outside. */
    private fun PolygonGeometry.signedBoundaryDistanceMeters(point: PolygonCoordinate): Double =
        signedBoundaryDistance(boundaryDistanceMeters(point), relationTo(point))

    private fun signedBoundaryDistance(
        boundaryDistanceMeters: Double,
        relation: PolygonPointRelation
    ): Double =
        if (relation == PolygonPointRelation.OUTSIDE) -boundaryDistanceMeters else boundaryDistanceMeters

    private companion object {
        /**
         * The arrival ceiling never falls below this, however shallow the ring. Looser than iOS,
         * whose ceiling is the venue depth alone.
         */
        const val MINIMUM_ARRIVAL_CEILING_METERS = 50.0

        /** Clearance a departure needs beyond the fix's own accuracy. Arrival has none. */
        const val DEPARTURE_BOUNDARY_MARGIN_METERS = 20.0
    }
}
