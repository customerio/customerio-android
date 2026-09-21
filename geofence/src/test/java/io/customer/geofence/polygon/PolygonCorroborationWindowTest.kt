package io.customer.geofence.polygon

import io.customer.geofence.PolygonArrivalExpiry
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

/**
 * Whether a marginal arrival can actually be confirmed at the cadence the field delivers fixes at,
 * and whether the second fix has to be a second measurement.
 *
 * The window is sized against the approach sampler's 15 s `UPDATE_INTERVAL_MS`. Captures from
 * 2026-09-17 to 2026-09-20 show the real gap between consecutive fixes for one fence at 300.7,
 * 301.0, 301.3, 305.8, 703.2 and 1539.4 s, measured as the `held` value on every `arrival.expired`
 * record, which is retired lazily on the next fix and so reports the gap itself. `cor=true` never
 * appears in any capture: no marginal arrival has ever been confirmed on a device.
 *
 * **The window is not the lever.** Widening it to reach 300 s contradicts
 * `process_givenTwoMarginalFixesMinutesApart_expectNoArrivalFromCombiningThem`, which Shahroz
 * reported on #882 with a reproduction and which pins that two marginal fixes `FIVE_MINUTES_NANOS`
 * apart must not combine into a visit. The field's corroborating gap and that defect's gap are the
 * same number, so at this cadence two fixes cannot distinguish one visit from two passes at all.
 * The sampler not delivering at its requested 15 s is the defect; see the arrival-sampler notes.
 *
 * The ring is the fence that lost the arrival, translated to a different latitude band with the
 * longitude deltas rescaled by the ratio of the two cosines, so every edge length and clearance is
 * metrically identical while the original position cannot be recovered from a constant offset.
 */
class PolygonCorroborationWindowTest {

    /**
     * The 2026-09-17 ring, the same geometry [PolygonCorroborationOnRealFencesTest] calls fence B.
     * The two field arrivals that expired were on other rings; this one is used because its
     * clearance and accuracy are already documented against a real fix, and the failure is the
     * same shape on any marginal ring.
     */
    private val fence = PolygonFence(
        id = "fence-b",
        geometry = PolygonGeometry.from(
            listOf(
                PolygonCoordinate(-8.908793, 22.430434),
                PolygonCoordinate(-8.910988, 22.430469),
                PolygonCoordinate(-8.910969, 22.432404),
                PolygonCoordinate(-8.908803, 22.432352)
            )
        )
    )

    /** 8.4 m inside the ring. Paired with an accuracy above that, so the arrival needs a second fix. */
    private val insideFix = PolygonCoordinate(-8.910190, 22.430533)

    /**
     * The same visit, sampled again. About 2 m north of [insideFix]: a second measurement of a
     * stationary device differs by its own jitter, which is the only thing that distinguishes it
     * from the same measurement delivered twice.
     */
    private val insideFixResampled = PolygonCoordinate(-8.910172, 22.430533)

    private val committedOutside = mapOf(fence.id to PolygonCommittedState.OUTSIDE)

    /** The accuracy this fix was recorded at, and above its 8.4 m clearance, so it is marginal. */
    private val marginalAccuracy = 18.0

    private fun sampleAt(coordinate: PolygonCoordinate) =
        PolygonLocationSample(coordinate, horizontalAccuracyMeters = marginalAccuracy)

    @Test
    fun process_givenAMarginalArrival_expectItIsHeldRatherThanCommitted() {
        val processor = PolygonRouteProcessor()

        val outcome = processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFix),
            elapsedRealtimeNanos = 0L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        outcome.detections shouldBeEqualTo emptyList()
        outcome.records.filterIsInstance<PolygonRouteRecord.ArrivalPending>().size shouldBeEqualTo 1
    }

    @Test
    fun process_givenASecondMeasurementAtTheFieldCadence_expectTheArrivalIsLost() {
        // Characterization of the live defect, not an endorsement of it. 301 s is the gap the
        // captures show between two fixes for one fence and the gap every lost arrival died in.
        // The hold is retired before the second fix is judged, so that fix starts a fresh hold
        // instead of completing the first, and at this cadence the cycle never terminates.
        val processor = PolygonRouteProcessor()
        processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFix),
            elapsedRealtimeNanos = 0L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        val outcome = processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFixResampled),
            elapsedRealtimeNanos = 301_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        outcome.detections shouldBeEqualTo emptyList()
        outcome.records.filterIsInstance<PolygonRouteRecord.ArrivalExpired>()
            .single().reason shouldBeEqualTo PolygonArrivalExpiry.WINDOW_ELAPSED
        // And it starts over rather than giving up, which is why the captures show the same fence
        // expiring at ~301 s again and again instead of once.
        outcome.records.filterIsInstance<PolygonRouteRecord.ArrivalPending>().size shouldBeEqualTo 1
    }

    @Test
    fun process_givenTheSamePositionAtADifferentAccuracy_expectNoConfirmation() {
        // The discriminator is the position, not the whole measurement. This is the case the
        // measured re-emission actually produces: the coordinate is carried forward and the
        // accuracy is substituted, so requiring both to match would let one observation confirm an
        // arrival. An identical coordinate answers "is it also inside" with the first fix.
        //
        // The accuracy here is a tier the capture shows 10 times, and it is under the ceiling, so
        // nothing downstream would have refused it.
        val processor = PolygonRouteProcessor()
        processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFix),
            elapsedRealtimeNanos = 0L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        val outcome = processor.process(
            fences = listOf(fence),
            sample = PolygonLocationSample(insideFix, horizontalAccuracyMeters = 20.0),
            elapsedRealtimeNanos = 15_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        outcome.detections shouldBeEqualTo emptyList()
        outcome.records.filterIsInstance<PolygonRouteRecord.ArrivalEcho>().size shouldBeEqualTo 1
    }

    @Test
    fun process_givenAnEchoInsideTheWindow_expectItDoesNotExtendTheHold() {
        // An echo must not buy the hold more time. If it did, a carried-forward position could keep
        // a hold alive until a genuine fix arrived minutes later, and the arrival would rest on one
        // observation the second fix never saw, which is #882 reached by another route.
        val processor = PolygonRouteProcessor()
        processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFix),
            elapsedRealtimeNanos = 0L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )
        processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFix),
            elapsedRealtimeNanos = 50_000_000_000L,
            fixAgeSeconds = 0.1,
            committedStates = committedOutside
        )

        // 70 s after the hold opened, so outside the window. Had the echo at 50 s refreshed the
        // stamp this would be 20 s after it and would complete the arrival instead.
        val outcome = processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFixResampled),
            elapsedRealtimeNanos = 70_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        outcome.detections shouldBeEqualTo emptyList()
        outcome.records.filterIsInstance<PolygonRouteRecord.ArrivalExpired>()
            .single().reason shouldBeEqualTo PolygonArrivalExpiry.WINDOW_ELAPSED
    }

    @Test
    fun process_givenACoarseReEmissionOfTheHeldPosition_expectItDoesNotBreakTheHold() {
        // The interaction that decides whether asking for a precise fix helps at all. The requested
        // fix comes back over the ceiling most of the time on this device, and a fix at the held
        // coordinate carries no information about containment whichever side of the ceiling its
        // accuracy label falls on. Reading it as evidence against the arrival destroys the hold
        // within seconds of opening it, so the visit is lost faster than before it was asked for.
        val processor = PolygonRouteProcessor()
        processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFix),
            elapsedRealtimeNanos = 0L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )
        processor.process(
            fences = listOf(fence),
            sample = PolygonLocationSample(insideFix, horizontalAccuracyMeters = 100.0),
            elapsedRealtimeNanos = 20_000_000_000L,
            fixAgeSeconds = 0.1,
            committedStates = committedOutside
        )

        val outcome = processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFixResampled),
            elapsedRealtimeNanos = 40_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        outcome.detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)
    }

    @Test
    fun process_givenTheSameMeasurementDeliveredTwice_expectNoConfirmation() {
        // The other half. The platform re-stamps a carried-forward sample, so the dedupe on
        // elapsed-realtime admits an echo as a new observation. Position and accuracy identical is
        // one measurement, whatever the stamp says, and it cannot corroborate itself.
        val processor = PolygonRouteProcessor()
        processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFix),
            elapsedRealtimeNanos = 0L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        val outcome = processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFix),
            elapsedRealtimeNanos = 50_000_000L,
            fixAgeSeconds = 0.1,
            committedStates = committedOutside
        )

        outcome.detections shouldBeEqualTo emptyList()
    }
}
