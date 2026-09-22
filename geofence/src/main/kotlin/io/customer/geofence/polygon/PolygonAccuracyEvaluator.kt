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
    /**
     * Positive inside, negative outside, as iOS reports it. Set on every evaluated result, decided,
     * undecided and agreeing alike, so a capture can compare the fixes a margin accepted against the
     * ones it refused. Null only where the geometry was never consulted.
     */
    val signedBoundaryDistanceMeters: Double? = null,
    /**
     * The fix decided, but its own uncertainty reaches the ring, so it cannot rule out having been
     * taken from the other side. A second agreeing fix settles it.
     */
    val requiresCorroboration: Boolean = false,
    /**
     * The fix was decisive and found the device on the side the committed state already claims, so
     * there is nothing to report. Recorded anyway: these are the fixes the margins let through, and
     * a capture of only the ones they refused cannot say where a margin should sit.
     */
    val agreedWithCommittedState: Boolean = false
)

/** Classifies a location fix without mutating committed polygon state. */
internal class PolygonAccuracyEvaluator {
    /**
     * Classifies a sparse background fix, asymmetrically.
     *
     * Arrival and departure are not equally costly. A missed arrival loses the visit outright,
     * because polygons are excluded from initial-ENTER synthesis and nothing re-derives it; a
     * spurious one costs a transition that the next decisive fix corrects. Departure is the
     * opposite: leaving early on a noisy fix ends a visit that is still happening.
     *
     * So arrival asks only that the fix itself lies inside the ring, and departure keeps the
     * clearance margin. The symmetric rule this replaces required `edge > accuracy + margin` in
     * both directions, which at median background accuracy leaves no decidable point anywhere
     * inside a 24 m fence — and three of our four real polygons are 24-42 m across.
     *
     * The three questions are asked in the order they can be answered: geometry, then clearance,
     * then precision. Precision is only ever a question about an arrival.
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
        // Clearance before accuracy. A fix clear of the ring by more than its own accuracy plus the
        // margin has settled which side of it the device is on, however coarse the fix is: a device
        // 988 m outside a ring is not ambiguous at 122 m accuracy. Reading the accuracy ceiling
        // first, as this did until 2026-09-18, discarded such a fix for being imprecise about a
        // distance nothing doubted, and a discarded departure leaves the visit open and then
        // suppresses the next arrival at that venue through the redundant-ENTER guard.
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
        // Only an arrival still needs the fix to be precise enough to mean anything. Departure has
        // already been answered above, so from here the ceiling governs arrivals alone.
        //
        // Per fence, and never stricter than the flat 50 m it replaces. The old constant refused a
        // 122 m fix 16 m inside a 254 m-deep venue, which is the one visit the field captures lost
        // outright, so the ceiling rises with the venue's own depth.
        //
        // It does not fall below 50 m, which is a deliberate departure from iOS. Scaling all the
        // way down makes a 24 m shop undecidable at the 20 m accuracy the background routinely
        // delivers, and that is the defect this module already fixed once: requiring the accuracy
        // circle to clear the ring made a retail unit permanently undetectable. Refusing a real
        // arrival is the expensive error, and the captures give no reason to pay it here — across
        // 20 evaluations of the two rings shallower than this floor, not one fix ever read inside,
        // so the band the floor keeps open has never yet admitted anything at all.
        //
        // A marginal fix admitted by the floor is not reported blindly: it opens a hold, and any
        // later fix that can judge the venue and reads outside discards it.
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
                    // Arrival carries no clearance margin, so nothing else bounds accuracy against
                    // the boundary. Measured on our own rings, the exterior band a single
                    // inside-reading fix can have come from is over twice the area of the polygon
                    // itself — that band is exactly this condition, seen from the other side.
                    requiresCorroboration =
                    boundaryDistanceMeters <= sample.horizontalAccuracyMeters
                )
            // Outside, and not clear of the ring by enough to have ruled out the other side.
            committedState == PolygonCommittedState.INSIDE && relation == PolygonPointRelation.OUTSIDE ->
                undecided(PolygonUndecidedReason.WITHIN_ACCURACY, signedBoundaryDistanceMeters)
            // Decisive, and it agrees with the committed state. Nothing to decide, so no reason and
            // no transition, but the fix quality is still worth recording.
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

    private companion object {
        /**
         * The arrival ceiling never falls below this, however shallow the ring.
         *
         * Carried over as the old flat ceiling's value so this change cannot refuse an arrival that
         * the previous rule accepted. It is the one place Android is deliberately looser than iOS,
         * whose ceiling is the venue depth alone.
         */
        const val MINIMUM_ARRIVAL_CEILING_METERS = 50.0

        /**
         * Clearance a departure needs beyond the fix's own accuracy. Arrival has no equivalent by
         * design. v1 value.
         */
        const val DEPARTURE_BOUNDARY_MARGIN_METERS = 20.0
    }
}
