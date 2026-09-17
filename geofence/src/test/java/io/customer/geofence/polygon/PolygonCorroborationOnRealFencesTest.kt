package io.customer.geofence.polygon

import io.customer.geofence.GeofenceLogger
import io.customer.sdk.core.util.CioLogLevel
import io.customer.sdk.core.util.Logger
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

/**
 * What corroboration costs on the workspace's own fences, at the accuracies the 2026-09-17 drive
 * actually produced.
 *
 * Characterization, not verification: every assertion here is an evaluator or route-processor
 * return value, so all of it passes with or without the instrumentation shipped alongside it. It is
 * here to give a calibration decision real numbers from real rings, and it must be read that way.
 *
 * Vertices and fix coordinates are copied from the device's own region store and capture. Fence 17
 * (Tim Hortons) and fence 25 (Al Fatah Electronics) are the extremes of the workspace, at 24 m and
 * 106 m maximum clearance.
 */
class PolygonCorroborationOnRealFencesTest {

    // Tim Hortons: a triangle. Its point of maximum clearance sits 24.2 m from the nearest edge and
    // no interior point does better, which is what bounds everything below.
    private val timHortons = PolygonGeometry.from(
        listOf(
            PolygonCoordinate(31.37435938826417, 74.18638229370117),
            PolygonCoordinate(31.3734721218287, 74.18640635754653),
            PolygonCoordinate(31.37281945576276, 74.18754093195983)
        )
    )

    // Al Fatah Electronics: the fence that lost the arrival.
    private val alFatah = PolygonGeometry.from(
        listOf(
            PolygonCoordinate(31.377207, 74.186845),
            PolygonCoordinate(31.375012, 74.186886),
            PolygonCoordinate(31.375031, 74.189125),
            PolygonCoordinate(31.377197, 74.189064)
        )
    )

    /** Tim Hortons' point of maximum clearance, 24.16 m from the nearest edge. */
    private val timHortonsBestPoint = PolygonCoordinate(31.373592, 74.186657)

    /** Al Fatah's point of maximum clearance, 105.93 m from the nearest edge. */
    private val alFatahBestPoint = PolygonCoordinate(31.376054, 74.187981)

    /**
     * The device's actual position at 18:09:37 on the drive, straight from the capture. It sits
     * 8.4 m inside the ring; iOS logged the same venue at `edge=+10` to zero decimal places.
     */
    private val alFatahDriveFix = PolygonCoordinate(31.37581, 74.18696)

    private val evaluator = PolygonAccuracyEvaluator()

    @Test
    fun timHortons_givenAccuracyAboveItsMaximumClearance_expectEvenTheBestPointIsHeld() {
        // 25 m beats the 24.2 m clearance of the most favourable point in the fence, so at this
        // accuracy nothing anywhere inside Tim Hortons can arrive on a single fix. Background
        // accuracy on this drive ran 13-24 m, so the fence spends real time in this state.
        val result = evaluator.decisiveEvidenceFor(
            geometry = timHortons,
            sample = PolygonLocationSample(timHortonsBestPoint, horizontalAccuracyMeters = 25.0),
            committedState = PolygonCommittedState.OUTSIDE
        )

        result.evidence shouldBeEqualTo PolygonEvidence.ENTER
        result.requiresCorroboration shouldBeEqualTo true
    }

    @Test
    fun timHortons_givenDriveAccuracy_expectOnlyTheBestPointCommitsAndOnlyJust() {
        // At 23.7 m, the accuracy that carried the Cakes & Bakes arrival, the single best point in
        // the fence clears by under half a metre and everywhere else is held. Committing at this
        // venue is technically possible and practically unreachable.
        val best = evaluator.decisiveEvidenceFor(
            geometry = timHortons,
            sample = PolygonLocationSample(timHortonsBestPoint, horizontalAccuracyMeters = 23.7),
            committedState = PolygonCommittedState.OUTSIDE
        )
        // 20 m north of it, still well inside the fence.
        val nearby = evaluator.decisiveEvidenceFor(
            geometry = timHortons,
            sample = PolygonLocationSample(
                PolygonCoordinate(31.373772, 74.186657),
                horizontalAccuracyMeters = 23.7
            ),
            committedState = PolygonCommittedState.OUTSIDE
        )

        best.requiresCorroboration shouldBeEqualTo false
        nearby.requiresCorroboration shouldBeEqualTo true
    }

    @Test
    fun alFatah_givenTheActualDriveFix_expectHeldNotCommitted() {
        // The lost arrival, replayed from the capture's own coordinates and accuracy.
        val result = evaluator.decisiveEvidenceFor(
            geometry = alFatah,
            sample = PolygonLocationSample(alFatahDriveFix, horizontalAccuracyMeters = 18.0),
            committedState = PolygonCommittedState.OUTSIDE
        )

        result.evidence shouldBeEqualTo PolygonEvidence.ENTER
        result.requiresCorroboration shouldBeEqualTo true
    }

    @Test
    fun alFatah_givenAFixWellInsideTheSameRing_expectCommitsOnOneFix() {
        // The control: same fence, same accuracy, 106 m of clearance instead of 8. Proves the held
        // verdict above is about where in the ring the device stood, not about the ring itself.
        val result = evaluator.decisiveEvidenceFor(
            geometry = alFatah,
            sample = PolygonLocationSample(alFatahBestPoint, horizontalAccuracyMeters = 18.0),
            committedState = PolygonCommittedState.OUTSIDE
        )

        result.evidence shouldBeEqualTo PolygonEvidence.ENTER
        result.requiresCorroboration shouldBeEqualTo false
    }

    @Test
    fun route_givenAHeldArrivalAndASecondFixWithinTheWindow_expectTheArrivalCommits() {
        // The design working as intended: the approach session samples every 15 s, so the
        // corroborating fix is normally the next one.
        val processor = PolygonRouteProcessor(logger = GeofenceLogger(SilentLogger()))
        val fence = PolygonFence("al-fatah", alFatah)

        val first = processor.process(
            fences = listOf(fence),
            sample = PolygonLocationSample(alFatahDriveFix, 18.0),
            elapsedRealtimeNanos = 1_000_000_000L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        )
        val second = processor.process(
            fences = listOf(fence),
            sample = PolygonLocationSample(alFatahDriveFix, 18.0),
            elapsedRealtimeNanos = 16_000_000_000L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        )

        first shouldBeEqualTo emptyList()
        second shouldBeEqualTo listOf(
            PolygonTransitionDetection("al-fatah", PolygonTransition.ENTER)
        )
    }

    @Test
    fun route_givenTheSecondFixArrivesAfterTheWindow_expectTheArrivalIsLost() {
        // The field failure as behaviour rather than as a story. The sampler went quiet for five
        // minutes, so the corroborating fix fell outside the 60 s window. The visit is real and
        // ongoing and this is still not an arrival: the count restarted rather than completing.
        val processor = PolygonRouteProcessor(logger = GeofenceLogger(SilentLogger()))
        val fence = PolygonFence("al-fatah", alFatah)

        processor.process(
            fences = listOf(fence),
            sample = PolygonLocationSample(alFatahDriveFix, 18.0),
            elapsedRealtimeNanos = 1_000_000_000L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        )
        val afterTheGap = processor.process(
            fences = listOf(fence),
            sample = PolygonLocationSample(alFatahDriveFix, 18.0),
            elapsedRealtimeNanos = 301_000_000_000L,
            fixAgeSeconds = 0.0,
            committedStates = emptyMap()
        )

        afterTheGap shouldBeEqualTo emptyList()
    }

    private class SilentLogger : Logger {
        override var logLevel: CioLogLevel = CioLogLevel.NONE

        override fun setLogDispatcher(dispatcher: ((CioLogLevel, String) -> Unit)?) = Unit

        override fun info(message: String, tag: String?) = Unit
        override fun debug(message: String, tag: String?) = Unit
        override fun error(message: String, tag: String?, throwable: Throwable?) = Unit
    }
}
