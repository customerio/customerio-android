package io.customer.geofence.polygon

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class PolygonAccuracyEvaluatorTest {
    private val evaluator = PolygonAccuracyEvaluator()
    private val geometry = PolygonGeometry.from(
        listOf(
            point(-0.001, -0.001),
            point(-0.001, 0.001),
            point(0.001, 0.001),
            point(0.001, -0.001)
        )
    )

    @Test
    fun decisiveEvidenceFor_whenAccuracyCircleAndMarginAreInside_thenReturnsEnter() {
        val sample = sample(latitude = 0.0, longitude = 0.0, accuracyMeters = 10.0)

        evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE).evidence shouldBeEqualTo
            PolygonEvidence.ENTER
    }

    @Test
    fun decisiveEvidenceFor_whenArrivingNearTheBoundary_thenReturnsEnter() {
        // ~11 m inside the ring at 5 m accuracy. Arrival needs no clearance margin.
        val sample = sample(latitude = 0.0, longitude = 0.0009, accuracyMeters = 5.0)

        evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE).evidence shouldBeEqualTo
            PolygonEvidence.ENTER
    }

    @Test
    fun decisiveEvidenceFor_whenDepartingNearTheBoundary_thenRemainsAmbiguous() {
        // ~11 m outside. Departure keeps the clearance margin.
        val sample = sample(latitude = 0.0, longitude = 0.0011, accuracyMeters = 5.0)

        evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.INSIDE).evidence shouldBeEqualTo
            PolygonEvidence.AMBIGUOUS
    }

    @Test
    fun decisiveEvidenceFor_whenDepartingWellClearOfTheBoundary_thenReturnsExit() {
        val sample = sample(latitude = 0.0, longitude = 0.0015, accuracyMeters = 5.0)

        evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.INSIDE).evidence shouldBeEqualTo
            PolygonEvidence.EXIT
    }

    @Test
    fun decisiveEvidenceFor_whenDepartingWellClearOfTheRingOnACoarseFix_thenStillReturnsExit() {
        // ~1.1 km out at 120 m accuracy: clearance is read before the accuracy ceiling.
        val sample = sample(latitude = 0.0, longitude = 0.011, accuracyMeters = 120.0)

        val result = evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.INSIDE)

        result.evidence shouldBeEqualTo PolygonEvidence.EXIT
        result.undecidedReason shouldBeEqualTo null
    }

    @Test
    fun decisiveEvidenceFor_whenAlreadyOutsideOnACoarseFix_thenAgreesInsteadOfRefusing() {
        // The same fix with nothing committed.
        val sample = sample(latitude = 0.0, longitude = 0.011, accuracyMeters = 120.0)

        val result = evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE)

        result.evidence shouldBeEqualTo PolygonEvidence.AMBIGUOUS
        result.agreedWithCommittedState shouldBeEqualTo true
        result.undecidedReason shouldBeEqualTo null
    }

    @Test
    fun decisiveEvidenceFor_whenDepartingOnACoarseFixNearTheRing_thenStillRefuses() {
        // ~56 m outside at 120 m accuracy: short of the margin, so it falls through to the ceiling.
        val sample = sample(latitude = 0.0, longitude = 0.0015, accuracyMeters = 120.0)

        val result = evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.INSIDE)

        result.evidence shouldBeEqualTo PolygonEvidence.AMBIGUOUS
        result.undecidedReason shouldBeEqualTo PolygonUndecidedReason.ACCURACY_TOO_LOW
    }

    @Test
    fun decisiveEvidenceFor_whenAccuracyIsPastTheFloor_thenRefusesTheArrival() {
        // [shallowGeometry] is 11 m deep, so the ceiling is the 50 m floor and 60 m is past it.
        val sample = sample(latitude = 0.0, longitude = 0.0, accuracyMeters = 60.0)

        val result = evaluator.decisiveEvidenceFor(shallowGeometry, sample, PolygonCommittedState.OUTSIDE)

        result.evidence shouldBeEqualTo PolygonEvidence.AMBIGUOUS
        result.undecidedReason shouldBeEqualTo PolygonUndecidedReason.ACCURACY_TOO_LOW
    }

    @Test
    fun decisiveEvidenceFor_whenTheVenueIsShallowerThanTheFloor_thenStillHoldsTheArrival() {
        // Held, not refused: refusing would make a venue this small undetectable in the background.
        val sample = sample(latitude = 0.0, longitude = 0.0, accuracyMeters = 30.0)

        val result = evaluator.decisiveEvidenceFor(shallowGeometry, sample, PolygonCommittedState.OUTSIDE)

        result.evidence shouldBeEqualTo PolygonEvidence.ENTER
        result.requiresCorroboration shouldBeEqualTo true
    }

    @Test
    fun decisiveEvidenceFor_whenTheVenueIsDeeperThanTheAccuracy_thenDecidesTheArrival() {
        // The same fix against a 111 m-deep venue: the ceiling scales with the fence's depth.
        val sample = sample(latitude = 0.0, longitude = 0.0, accuracyMeters = 60.0)

        val result = evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE)

        result.evidence shouldBeEqualTo PolygonEvidence.ENTER
        // A 60 m circle at a point 111 m from every edge lies wholly inside the ring.
        result.requiresCorroboration shouldBeEqualTo false
    }

    @Test
    fun decisiveEvidenceFor_whenTheVenueIsDeepButTheFixIsNearItsEdge_thenHoldsTheArrival() {
        // 33 m inside a 111 m-deep ring at 60 m accuracy: closer to the edge than the uncertainty.
        val sample = sample(latitude = 0.0, longitude = 0.0007, accuracyMeters = 60.0)

        val result = evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE)

        result.evidence shouldBeEqualTo PolygonEvidence.ENTER
        result.requiresCorroboration shouldBeEqualTo true
    }

    /** Roughly 22 m to a side, so 11 m deep: shallower than the 50 m arrival-ceiling floor. */
    private val shallowGeometry = PolygonGeometry.from(
        listOf(
            point(-0.0001, -0.0001),
            point(-0.0001, 0.0001),
            point(0.0001, 0.0001),
            point(0.0001, -0.0001)
        )
    )

    private val wideGeometry = PolygonGeometry.from(
        listOf(
            point(-0.01, -0.01),
            point(-0.01, 0.01),
            point(0.01, 0.01),
            point(0.01, -0.01)
        )
    )

    private fun sample(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double
    ) = PolygonLocationSample(
        coordinate = point(latitude, longitude),
        horizontalAccuracyMeters = accuracyMeters
    )

    private fun point(latitude: Double, longitude: Double) =
        PolygonCoordinate(latitude = latitude, longitude = longitude)
}
