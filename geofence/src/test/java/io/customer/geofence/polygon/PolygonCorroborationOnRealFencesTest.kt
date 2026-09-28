package io.customer.geofence.polygon

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInRange
import org.junit.Test

/**
 * Characterizes corroboration on two fence shapes taken from real venues (24 m and 106 m maximum
 * clearance) at coarse background accuracies.
 *
 * Coordinates are relocated to another latitude band with longitude deltas rescaled by the cosine
 * ratio, so edge lengths and clearances match the originals but the real positions cannot be
 * recovered from a constant offset.
 */
class PolygonCorroborationOnRealFencesTest {

    // Fence A: a triangle whose best interior point is 24.2 m from the nearest edge.
    private val fenceA = PolygonGeometry.from(
        listOf(
            PolygonCoordinate(-8.911641, 22.430034),
            PolygonCoordinate(-8.912528, 22.430055),
            PolygonCoordinate(-8.913181, 22.431035)
        )
    )

    // Fence B: a quadrilateral with 106 m maximum clearance.
    private val fenceB = PolygonGeometry.from(
        listOf(
            PolygonCoordinate(-8.908793, 22.430434),
            PolygonCoordinate(-8.910988, 22.430469),
            PolygonCoordinate(-8.910969, 22.432404),
            PolygonCoordinate(-8.908803, 22.432352)
        )
    )

    /** Fence A's point of maximum clearance, 24.16 m from the nearest edge. */
    private val fenceABestPoint = PolygonCoordinate(-8.912408, 22.430271)

    /** Fence B's point of maximum clearance, 105.93 m from the nearest edge. */
    private val fenceBBestPoint = PolygonCoordinate(-8.909946, 22.431416)

    /** A fix 8.4 m inside Fence B, relocated with the rings. */
    private val fenceBDriveFix = PolygonCoordinate(-8.910190, 22.430533)

    private val evaluator = PolygonAccuracyEvaluator()

    @Test
    fun fenceA_givenAccuracyAboveItsMaximumClearance_expectEvenTheBestPointIsHeld() {
        // 25 m exceeds the fence's 24.2 m best clearance, so no single fix inside it commits. The
        // 50 m ceiling floor still admits the fix and holds it; a ceiling at venue depth would
        // refuse any fix coarser than the venue's ~24 m depth.
        val result = evaluator.decisiveEvidenceFor(
            geometry = fenceA,
            sample = PolygonLocationSample(fenceABestPoint, horizontalAccuracyMeters = 25.0),
            committedState = PolygonCommittedState.OUTSIDE
        )

        result.evidence shouldBeEqualTo PolygonEvidence.ENTER
        result.requiresCorroboration shouldBeEqualTo true
    }

    @Test
    fun fenceA_givenAccuracyPastTheFloor_expectTheArrivalIsRefused() {
        // Fence A's ceiling is the 50 m floor, so 60 m is refused regardless of position.
        val result = evaluator.decisiveEvidenceFor(
            geometry = fenceA,
            sample = PolygonLocationSample(fenceABestPoint, horizontalAccuracyMeters = 60.0),
            committedState = PolygonCommittedState.OUTSIDE
        )

        result.evidence shouldBeEqualTo PolygonEvidence.AMBIGUOUS
        result.undecidedReason shouldBeEqualTo PolygonUndecidedReason.ACCURACY_TOO_LOW
    }

    @Test
    fun venueScale_givenTheRealRings_expectItTracksTheirMeasuredClearance() {
        // venueScaleMeters approximates the maximum inradius; it must err high (wider accepted
        // accuracy) rather than refuse real arrivals.
        fenceA.venueScaleMeters shouldBeInRange 24.0..24.5
        fenceB.venueScaleMeters shouldBeInRange 112.0..113.5
        // Grid-computed maximum clearances.
        (fenceA.venueScaleMeters >= 24.16) shouldBeEqualTo true
        (fenceB.venueScaleMeters >= 105.93) shouldBeEqualTo true
    }

    @Test
    fun fenceA_givenDriveAccuracy_expectOnlyTheBestPointCommitsAndOnlyJust() {
        // At 23.7 m only the best point clears the ring, by under half a metre; everywhere else is
        // held.
        val best = evaluator.decisiveEvidenceFor(
            geometry = fenceA,
            sample = PolygonLocationSample(fenceABestPoint, horizontalAccuracyMeters = 23.7),
            committedState = PolygonCommittedState.OUTSIDE
        )
        // 20 m north of it, still well inside the fence.
        val nearby = evaluator.decisiveEvidenceFor(
            geometry = fenceA,
            sample = PolygonLocationSample(
                PolygonCoordinate(-8.912228, 22.430271),
                horizontalAccuracyMeters = 23.7
            ),
            committedState = PolygonCommittedState.OUTSIDE
        )

        best.requiresCorroboration shouldBeEqualTo false
        nearby.requiresCorroboration shouldBeEqualTo true
    }

    @Test
    fun fenceB_givenTheActualDriveFix_expectHeldNotCommitted() {
        // 8.4 m clearance at 18 m accuracy.
        val result = evaluator.decisiveEvidenceFor(
            geometry = fenceB,
            sample = PolygonLocationSample(fenceBDriveFix, horizontalAccuracyMeters = 18.0),
            committedState = PolygonCommittedState.OUTSIDE
        )

        result.evidence shouldBeEqualTo PolygonEvidence.ENTER
        result.requiresCorroboration shouldBeEqualTo true
    }

    @Test
    fun fenceB_givenAFixWellInsideTheSameRing_expectCommitsOnOneFix() {
        // Control for the test above: same fence and accuracy, 106 m of clearance instead of 8.
        val result = evaluator.decisiveEvidenceFor(
            geometry = fenceB,
            sample = PolygonLocationSample(fenceBBestPoint, horizontalAccuracyMeters = 18.0),
            committedState = PolygonCommittedState.OUTSIDE
        )

        result.evidence shouldBeEqualTo PolygonEvidence.ENTER
        result.requiresCorroboration shouldBeEqualTo false
    }

    @Test
    fun route_givenAHeldArrivalAndASecondFixWithinTheWindow_expectTheArrivalCommits() {
        // 15 s apart, the approach session's sampling interval.
        val processor = PolygonRouteProcessor()
        val fence = PolygonFence("fence-b", fenceB)

        val first = processor.process(
            fences = listOf(fence),
            sample = PolygonLocationSample(fenceBDriveFix, 18.0),
            elapsedRealtimeNanos = 1_000_000_000L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        )
        val second = processor.process(
            // About 2 m north, so it counts as an independent fix rather than the held one
            // re-delivered.
            fences = listOf(fence),
            sample = PolygonLocationSample(
                fenceBDriveFix.copy(latitude = fenceBDriveFix.latitude + 0.000018),
                18.0
            ),
            elapsedRealtimeNanos = 16_000_000_000L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        )

        first.detections shouldBeEqualTo emptyList()
        second.detections shouldBeEqualTo listOf(
            PolygonTransitionDetection("fence-b", PolygonTransition.ENTER)
        )
    }

    @Test
    fun route_givenTheSecondFixArrivesAfterTheWindow_expectTheArrivalIsLost() {
        // A 300 s gap exceeds the 60 s window, so the stale hold is dropped and this fix opens a
        // new one instead of committing.
        val processor = PolygonRouteProcessor()
        val fence = PolygonFence("fence-b", fenceB)

        processor.process(
            fences = listOf(fence),
            sample = PolygonLocationSample(fenceBDriveFix, 18.0),
            elapsedRealtimeNanos = 1_000_000_000L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        )
        val afterTheGap = processor.process(
            fences = listOf(fence),
            sample = PolygonLocationSample(fenceBDriveFix, 18.0),
            elapsedRealtimeNanos = 301_000_000_000L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        )

        afterTheGap.detections shouldBeEqualTo emptyList()
    }
}
