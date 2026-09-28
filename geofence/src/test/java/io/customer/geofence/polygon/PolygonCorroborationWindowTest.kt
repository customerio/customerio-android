package io.customer.geofence.polygon

import io.customer.geofence.PolygonArrivalCommit
import io.customer.geofence.PolygonArrivalExpiry
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

/**
 * How a held (marginal) arrival is settled: it is reported unless a judged fix reads outside or
 * the 60 s window elapses.
 *
 * Requiring a second agreeing fix fails for a stationary device: the fused provider re-emits the
 * coordinate it already carried, which is not an independent fix.
 */
class PolygonCorroborationWindowTest {

    /** Same relocated geometry as fence B in [PolygonCorroborationOnRealFencesTest]. */
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

    /** About 2 m north of [insideFix], so it is an independent fix rather than a re-delivery. */
    private val insideFixResampled = PolygonCoordinate(-8.910172, 22.430533)

    /** 47.3 m outside the ring, beyond a 20 m fix's accuracy plus the 20 m departure margin. */
    private val clearlyOutsideFix = PolygonCoordinate(-8.908373, 22.431400)

    /**
     * 8.3 m outside the ring: less than a 15 m fix's own accuracy, so it never satisfies the
     * departure clearance, yet it still breaks a held arrival.
     */
    private val justOutsideFix = PolygonCoordinate(-8.908723, 22.431400)

    private val committedOutside = mapOf(fence.id to PolygonCommittedState.OUTSIDE)

    /** Above [insideFix]'s 8.4 m clearance, so the arrival is marginal. */
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
        // The only way a hold is dropped without contradicting evidence: past the 60 s window the
        // held fix no longer describes where the device is, so this fix opens its own hold.
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
        // The fix is judged before the clock, so the window boundary cannot change what a
        // contradicting fix means.
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
        // Any judged fix reading outside breaks the hold. Requiring departure clearance (accuracy
        // plus 20 m) instead would commit this false arrival and keep it open until a fix cleared
        // the ring by that much.
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
        // Reads 47 m outside, but 150 m accuracy exceeds this venue's 113 m ceiling, so the fix is
        // unjudged and cannot break the hold.
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
        // The releasing fix could read outside; reporting it would put a negative edge on an ENTER.
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
        (decided.signedBoundaryDistanceMeters!! > 0.0) shouldBeEqualTo true
        // 0.4 s old when held, plus the 15 s the hold waited.
        decided.fixAgeSeconds shouldBeEqualTo 15.4
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
        // A stationary device: the provider re-emits the held coordinate with a substituted
        // accuracy. Not a second opinion, but not evidence against the arrival either.
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
        // A different position, but too coarse for a 113 m venue: it neither supports nor
        // contradicts the hold.
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
    fun process_givenTheSameMeasurementDeliveredTwice_expectTheStampDedupeRefusesIt() {
        // A re-delivery under the same elapsed-realtime stamp is refused before the hold is
        // consulted, so a duplicate broadcast cannot settle an arrival either way.
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

    @Test
    fun process_givenThePreciseAnswerIsTheHeldFixItself_expectTheArrivalIsReportedUnconfirmed() {
        // GMS answers a hold's precise-fix request with the fix it just delivered, stamp and all,
        // when that fix is under a second old. Refusing it as not-newer would leave the hold
        // waiting for a fix minutes away.
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
            committedStates = committedOutside,
            answersHeldFixAt = 50_000_000L
        )

        outcome.detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)
        outcome.records.filterIsInstance<PolygonRouteRecord.Decided>()
            .single().uncorroboratedReason shouldBeEqualTo PolygonArrivalCommit.NOT_INDEPENDENT
    }

    @Test
    fun process_givenThePreciseAnswerIsOlderThanTheHeldFix_expectTheStampDedupeStillRefusesIt() {
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
            elapsedRealtimeNanos = 49_999_999L,
            fixAgeSeconds = 0.1,
            committedStates = committedOutside,
            answersHeldFixAt = 49_999_999L
        )

        outcome.detections shouldBeEqualTo emptyList()
        outcome.records shouldBeEqualTo emptyList()
    }

    @Test
    fun process_givenThePreciseAnswerRepeatsAFixWithNoHoldOpen_expectTheStampDedupeStillRefusesIt() {
        // Decisive, so the first pass enters outright and opens no hold for the answer to settle.
        val processor = PolygonRouteProcessor()
        val decisive = PolygonLocationSample(insideFix, horizontalAccuracyMeters = 3.0)
        processor.process(
            fences = listOf(fence),
            sample = decisive,
            elapsedRealtimeNanos = 50_000_000L,
            fixAgeSeconds = 0.3,
            committedStates = committedOutside
        ).detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)

        val outcome = processor.process(
            fences = listOf(fence),
            sample = decisive,
            elapsedRealtimeNanos = 50_000_000L,
            fixAgeSeconds = 0.1,
            committedStates = committedOutside,
            answersHeldFixAt = 50_000_000L
        )

        outcome.detections shouldBeEqualTo emptyList()
        outcome.records shouldBeEqualTo emptyList()
    }
}
