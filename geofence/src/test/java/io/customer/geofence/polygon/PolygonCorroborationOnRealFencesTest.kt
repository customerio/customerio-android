package io.customer.geofence.polygon

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInRange
import org.junit.Test

/**
 * What corroboration costs on the test workspace's own fence shapes, at the accuracies the
 * 2026-09-17 drive actually produced.
 *
 * Characterization, not verification: every assertion here is an evaluator or route-processor
 * return value, so all of it passes with or without the instrumentation shipped alongside it. It is
 * here to give a calibration decision real numbers from real rings, and it must be read that way.
 *
 * The two rings are the extremes of the test workspace at 24 m and 106 m maximum clearance, and the
 * fix is the one that lost the arrival. The coordinates are not real ones: they are moved to a
 * different latitude band with the longitude deltas rescaled by the ratio of the two cosines, so
 * every edge length, clearance and assertion below is metrically identical while the original
 * positions cannot be recovered from a constant offset. Edge lengths were checked against the
 * originals before and after.
 */
class PolygonCorroborationOnRealFencesTest {

    // Fence A: a triangle. Its point of maximum clearance sits 24.2 m from the nearest edge and
    // no interior point does better, which is what bounds everything below.
    private val fenceA = PolygonGeometry.from(
        listOf(
            PolygonCoordinate(-8.911641, 22.430034),
            PolygonCoordinate(-8.912528, 22.430055),
            PolygonCoordinate(-8.913181, 22.431035)
        )
    )

    // Fence B: the fence that lost the arrival.
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

    /**
     * The device position that produced the lost arrival, translated with the rings. It sits 8.4 m
     * inside the ring; the iOS device recorded `edge=+10` for the same crossing at zero decimals.
     */
    private val fenceBDriveFix = PolygonCoordinate(-8.910190, 22.430533)

    private val evaluator = PolygonAccuracyEvaluator()

    @Test
    fun fenceA_givenAccuracyAboveItsMaximumClearance_expectEvenTheBestPointIsHeld() {
        // 25 m beats the 24.2 m clearance of the most favourable point in the fence, so nothing
        // anywhere inside Fence A arrives on a single fix at this accuracy. It is still admitted
        // and held, because the ceiling does not fall below 50 m however shallow the ring.
        //
        // Scaling the ceiling all the way down to the venue depth would refuse this outright, and
        // with it every background fix on a 24 m shop, since this drive's accuracy ran 13-24 m.
        // The floor is what keeps such a venue detectable at all; what stops the verdict being a
        // coin flip is the hold, which any later fix that can judge the ring and reads outside
        // discards.
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
        // Above the floor the ceiling does bite, on any ring. 60 m against a 24 m fence cannot say
        // anything about containment at all.
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
        // The ceiling is an O(n) approximation of the maximum inradius, so what matters is that it
        // tracks the grid-computed clearance on real shapes and errs high rather than low. Erring
        // high widens the accuracy accepted, and refusing a real arrival is the expensive error.
        fenceA.venueScaleMeters shouldBeInRange 24.0..24.5
        fenceB.venueScaleMeters shouldBeInRange 112.0..113.5
        // 1.001x on the triangle and 1.067x on the quadrilateral, against 24.16 m and 105.93 m.
        (fenceA.venueScaleMeters >= 24.16) shouldBeEqualTo true
        (fenceB.venueScaleMeters >= 105.93) shouldBeEqualTo true
    }

    @Test
    fun fenceA_givenDriveAccuracy_expectOnlyTheBestPointCommitsAndOnlyJust() {
        // At 23.7 m, the accuracy that carried a real arrival on a larger fence, the single best point in
        // the fence clears by under half a metre and everywhere else is held. Committing at this
        // venue is technically possible and practically unreachable.
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
        // The lost arrival, replayed at the capture's own clearance and accuracy.
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
        // The control: same fence, same accuracy, 106 m of clearance instead of 8. Proves the held
        // verdict above is about where in the ring the device stood, not about the ring itself.
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
        // The design working as intended: the approach session samples every 15 s, so the
        // corroborating fix is normally the next one.
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
            // About 2 m north of the first: the next sample of a real visit differs by its own
            // jitter, which is what separates a second measurement from the held one re-delivered.
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
        // The field failure as behaviour rather than as a story. The sampler went quiet for five
        // minutes, so the corroborating fix fell outside the 60 s window. The visit is real and
        // ongoing and this is still not an arrival: the count restarted rather than completing.
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
