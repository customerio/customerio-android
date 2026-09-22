package io.customer.geofence.polygon

import io.customer.geofence.PolygonArrivalCommit
import io.customer.geofence.PolygonArrivalExpiry
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

/**
 * What settles a marginal arrival, now that only a fix positively outside can lose one.
 *
 * Requiring a second fix to AGREE was measured against four captures and never once confirmed an
 * arrival: `cor=true` does not appear in any of them. The reason is structural rather than a
 * cadence to be tuned. A stationary device is exactly the case that cannot supply a second
 * measurement, because the fused provider re-emits the coordinate it already carried under a fresh
 * stamp, and the gap between two genuine fixes for one fence measured 300.7, 301.0, 301.3, 305.8,
 * 703.2 and 1539.4 s — the same order as the gap in
 * `process_givenTwoMarginalFixesMinutesApart_expectNoArrivalFromCombiningThem`, which pins that two
 * fixes that far apart must not combine into one visit. So two fixes cannot both confirm a visit
 * and distinguish it from two passes, and the confirmation requirement was the half to give up.
 *
 * What replaces it is iOS's rule: report the arrival unless a fix positively places the device
 * outside. The captures show that fix does arrive when the arrival was false — the two holds that
 * ever expired were followed by fixes reading 46.7 m and 1129.2 m outside at 20.0 m and 36.9 m
 * accuracy — so the case this gives up is narrower than it looks.
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

    /**
     * 47.3 m clear of the ring to the north, beyond its own 20 m accuracy plus the 20 m departure
     * margin. Copies the fix that followed fence 26's expired hold in the 2026-09-19 capture, which
     * read 46.7 m outside at 20.0 m accuracy.
     */
    private val clearlyOutsideFix = PolygonCoordinate(-8.908373, 22.431400)

    /**
     * 8.3 m outside the ring: reading outside, but by less than a 15 m fix's own accuracy, so it
     * never satisfies the departure clearance. iOS blocks an arrival on this; the departure rule
     * does not, which is why the two are separate questions.
     */
    private val justOutsideFix = PolygonCoordinate(-8.908723, 22.431400)

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
    fun process_givenASecondMeasurementPastTheWindow_expectTheArrivalIsLost() {
        // The one case that still loses a visit, and it needs a full minute with no fix at all to
        // reach. 301 s is the gap the captures show between two fixes for one fence. Past the
        // window the held fix no longer describes where the device is, so it is dropped rather than
        // reported at a time nothing can defend, and this fix opens a hold of its own.
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
        outcome.records.filterIsInstance<PolygonRouteRecord.ArrivalPending>().size shouldBeEqualTo 1
    }

    @Test
    fun process_givenAFixPositivelyOutside_expectTheArrivalIsAbandoned() {
        // The guard that keeps the new rule honest, and the case the captures actually contain.
        // Fence 26 held an arrival reading 24.4 m inside at 33.9 m accuracy and the next fix read
        // 46.7 m outside at 20.0 m: the device was never in it. This fixture is that fix, 47.3 m
        // clear of the ring, which is beyond its own accuracy plus the departure margin.
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
            sample = PolygonLocationSample(clearlyOutsideFix, horizontalAccuracyMeters = 20.0),
            elapsedRealtimeNanos = 15_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        outcome.detections shouldBeEqualTo emptyList()
        outcome.records.filterIsInstance<PolygonRouteRecord.ArrivalExpired>()
            .single().reason shouldBeEqualTo PolygonArrivalExpiry.EVIDENCE_BROKEN
    }

    @Test
    fun process_givenAFixPositivelyOutsideLongAfterTheWindow_expectItStillReadsAsContradiction() {
        // The incoming fix is judged before the clock is consulted, so the arbitrary window
        // boundary cannot change what a contradicting fix means. Before this ordering the same fix
        // reading 47 m outside broke the arrival at 59 s and passed it through at 61 s.
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
            sample = PolygonLocationSample(clearlyOutsideFix, horizontalAccuracyMeters = 20.0),
            elapsedRealtimeNanos = 301_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        outcome.records.filterIsInstance<PolygonRouteRecord.ArrivalExpired>()
            .single().reason shouldBeEqualTo PolygonArrivalExpiry.EVIDENCE_BROKEN
    }

    @Test
    fun process_givenAFixReadingOutsideWithoutDepartureClearance_expectTheArrivalIsAbandoned() {
        // The arrival rule is NOT the departure rule. Any fix that could judge this venue and read
        // outside blocks the arrival, which is iOS's predicate. Requiring the departure clearance
        // here instead — beyond the fix's own accuracy plus 20 m — would commit this arrival, and
        // at indoor accuracy the false visit would then stay open until something cleared the ring
        // by 120 m.
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
            sample = PolygonLocationSample(justOutsideFix, horizontalAccuracyMeters = 15.0),
            elapsedRealtimeNanos = 15_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        outcome.detections shouldBeEqualTo emptyList()
        outcome.records.filterIsInstance<PolygonRouteRecord.ArrivalExpired>()
            .single().reason shouldBeEqualTo PolygonArrivalExpiry.EVIDENCE_BROKEN
    }

    @Test
    fun process_givenAnOutsideReadingTooCoarseToJudge_expectTheArrivalIsStillReported() {
        // Both halves of the predicate are load-bearing. This fix reads 47 m outside, which on its
        // own looks like a contradiction, but at 150 m accuracy against a 113 m-deep venue it
        // cannot separate the sides at all, so it is not evidence of anything. iOS commits here for
        // the same reason, returning unconfirmed(accuracyTooLow) rather than contradicted.
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
            sample = PolygonLocationSample(clearlyOutsideFix, horizontalAccuracyMeters = 150.0),
            elapsedRealtimeNanos = 15_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        outcome.detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)
        outcome.records.filterIsInstance<PolygonRouteRecord.Decided>()
            .single().uncorroboratedReason shouldBeEqualTo PolygonArrivalCommit.UNJUDGEABLE
    }

    @Test
    fun process_givenACommittedArrival_expectTheRecordDescribesTheHeldFixNotTheReleasingOne() {
        // The verdict has to describe the observation it rests on. Reporting the releasing fix put
        // a negative edge on a polygon.decided ENTER, a record that contradicts itself.
        val processor = PolygonRouteProcessor()
        processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFix),
            elapsedRealtimeNanos = 0L,
            fixAgeSeconds = 0.4,
            committedStates = committedOutside
        )

        val outcome = processor.process(
            fences = listOf(fence),
            sample = PolygonLocationSample(insideFix, horizontalAccuracyMeters = 100.0),
            elapsedRealtimeNanos = 15_000_000_000L,
            fixAgeSeconds = 2.7,
            committedStates = committedOutside
        )

        val decided = outcome.records.filterIsInstance<PolygonRouteRecord.Decided>().single()
        decided.horizontalAccuracyMeters shouldBeEqualTo marginalAccuracy
        decided.fixAgeSeconds shouldBeEqualTo 0.4
        (decided.signedBoundaryDistanceMeters!! > 0.0) shouldBeEqualTo true
    }

    @Test
    fun process_givenASecondMeasurementThatAgrees_expectTheArrivalIsCorroborated() {
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
            elapsedRealtimeNanos = 15_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        outcome.detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)
        val decided = outcome.records.filterIsInstance<PolygonRouteRecord.Decided>().single()
        decided.corroborated shouldBeEqualTo true
        decided.uncorroboratedReason shouldBeEqualTo null
    }

    @Test
    fun process_givenTheHeldPositionRepeated_expectTheArrivalIsReportedUnconfirmed() {
        // A stationary device's own case. The provider carries the coordinate forward and
        // substitutes the accuracy, so this is one observation delivered twice and never a second
        // opinion. It is still not evidence the device is elsewhere, so the arrival is reported and
        // the record says which kind of silence it rests on.
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
            sample = PolygonLocationSample(insideFix, horizontalAccuracyMeters = 100.0),
            elapsedRealtimeNanos = 15_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        outcome.detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)
        val decided = outcome.records.filterIsInstance<PolygonRouteRecord.Decided>().single()
        decided.corroborated shouldBeEqualTo false
        decided.uncorroboratedReason shouldBeEqualTo PolygonArrivalCommit.NOT_INDEPENDENT
    }

    @Test
    fun process_givenAFixThatCannotJudgeTheVenue_expectTheArrivalIsReportedUnconfirmed() {
        // A different position this time, and too coarse to separate the sides of a 113 m venue. It
        // adds nothing to the fix being held, which is not the same as arguing against it. Reading
        // it as a broken run destroyed holds seconds after opening them, and it is the commonest
        // second fix on this device: 407 of 424 refusals in the captures are this accuracy tier.
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
            sample = PolygonLocationSample(insideFixResampled, horizontalAccuracyMeters = 150.0),
            elapsedRealtimeNanos = 15_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        )

        outcome.detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)
        outcome.records.filterIsInstance<PolygonRouteRecord.Decided>()
            .single().uncorroboratedReason shouldBeEqualTo PolygonArrivalCommit.UNJUDGEABLE
    }

    @Test
    fun process_givenTheFenceCommittedInsideWhileHeld_expectNoSecondEnter() {
        // A hold opens only while the fence is committed outside, but it does not end there: a sync
        // seeding containment commits a polygon inside without this processor deciding it. Reporting
        // the hold then would be a second ENTER for a visit already running.
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
            elapsedRealtimeNanos = 15_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = mapOf(fence.id to PolygonCommittedState.INSIDE)
        )

        outcome.detections shouldBeEqualTo emptyList()
        outcome.records.filterIsInstance<PolygonRouteRecord.ArrivalExpired>()
            .single().reason shouldBeEqualTo PolygonArrivalExpiry.ALREADY_INSIDE
    }

    @Test
    fun process_givenTheFenceCommittedInsideAndAFixOutside_expectTheDepartureStillFires() {
        // The defect a surviving mutant found. Ending the pass as soon as a hold was settled meant
        // an open hold swallowed a departure: a fix that positively places the device outside is
        // exactly the fix that should emit EXIT once the fence is committed inside, and the hold
        // being open is no reason to drop it.
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
            sample = PolygonLocationSample(clearlyOutsideFix, horizontalAccuracyMeters = 20.0),
            elapsedRealtimeNanos = 15_000_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = mapOf(fence.id to PolygonCommittedState.INSIDE)
        )

        outcome.detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.EXIT)
        outcome.records.filterIsInstance<PolygonRouteRecord.ArrivalExpired>()
            .single().reason shouldBeEqualTo PolygonArrivalExpiry.ALREADY_INSIDE
    }

    @Test
    fun process_givenTheSameMeasurementDeliveredTwice_expectTheStampDedupeRefusesIt() {
        // Unchanged by the new rule and load-bearing for it: a re-delivery under the SAME
        // elapsed-realtime stamp is refused before the hold is consulted at all, so a broadcast
        // delivered twice cannot settle an arrival either way.
        val processor = PolygonRouteProcessor()
        processor.process(
            fences = listOf(fence),
            sample = sampleAt(insideFix),
            elapsedRealtimeNanos = 50_000_000L,
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
        outcome.records shouldBeEqualTo emptyList()
    }
}
