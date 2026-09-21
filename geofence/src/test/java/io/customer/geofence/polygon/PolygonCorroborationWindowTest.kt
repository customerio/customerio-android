package io.customer.geofence.polygon

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

/**
 * Whether a marginal arrival can actually be confirmed at the cadence the field delivers fixes at,
 * and whether the second fix has to be a second measurement.
 *
 * The window was sized against a 15 s approach sampler. Captures from 2026-09-17 to 2026-09-20 show
 * the real gap between consecutive fixes for one fence at 300.7, 301.0, 301.3, 305.8, 703.2 and
 * 1539.4 s, measured as the `held` value on every `arrival.expired` record, which is retired lazily
 * on the next fix and so reports the gap itself. `cor=true` never appears in any capture: no
 * marginal arrival has ever been confirmed on a device.
 *
 * The ring is the fence that lost the arrival, translated to a different latitude band with the
 * longitude deltas rescaled by the ratio of the two cosines, so every edge length and clearance is
 * metrically identical while the original position cannot be recovered from a constant offset.
 */
class PolygonCorroborationWindowTest {

    /** The ring that held and then dropped a real arrival. */
    private val fence = PolygonFence(
        id = "16",
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

    /** Above the 8.4 m clearance, so the evaluator asks for corroboration. Matches a field record. */
    private val marginalAccuracy = 10.9

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
    fun process_givenASecondMeasurementAtTheFieldCadence_expectTheArrivalIsConfirmed() {
        // The defect. 301 s is the gap the captures actually show between two fixes for one fence,
        // and it is the gap every lost arrival died in. A second measurement of the same visit has
        // to be able to confirm it, or no marginal arrival is ever reportable.
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

        outcome.detections.map { it.transition } shouldBeEqualTo listOf(PolygonTransition.ENTER)
    }

    @Test
    fun process_givenTheSamePositionAtADifferentAccuracy_expectTheArrivalIsConfirmed() {
        // The discriminator is the whole measurement, not the position. A second fix that agrees on
        // where the device is but carries its own accuracy is a second measurement, and a parked
        // device reporting the same coordinate twice is the commonest way a real visit corroborates.
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
            sample = PolygonLocationSample(insideFix, horizontalAccuracyMeters = 9.8),
            elapsedRealtimeNanos = 15_000_000_000L,
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
