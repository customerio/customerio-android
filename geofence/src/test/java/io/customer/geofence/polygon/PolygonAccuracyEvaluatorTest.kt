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
        // ~11 m inside the ring at 5 m accuracy. The old symmetric rule refused this, which on a
        // 24-42 m retail fence refused every point in it.
        val sample = sample(latitude = 0.0, longitude = 0.0009, accuracyMeters = 5.0)

        evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE).evidence shouldBeEqualTo
            PolygonEvidence.ENTER
    }

    @Test
    fun decisiveEvidenceFor_whenDepartingNearTheBoundary_thenRemainsAmbiguous() {
        // The mirror image, and the whole of the asymmetry: same distance from the ring, same
        // accuracy, opposite direction. A visit in progress is not ended by a marginal fix.
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
        // The defect this rule order fixes, and the case the field produced eight times across four
        // drives. The fix is coarser than the whole venue and still not in any doubt about being
        // outside it: roughly 1.1 km of clearance against 120 m of uncertainty. The accuracy
        // ceiling used to be read first and discarded exactly this, and a discarded departure
        // leaves the visit open, which then suppresses the next arrival at that venue.
        val sample = sample(latitude = 0.0, longitude = 0.011, accuracyMeters = 120.0)

        val result = evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.INSIDE)

        result.evidence shouldBeEqualTo PolygonEvidence.EXIT
        result.undecidedReason shouldBeEqualTo null
    }

    @Test
    fun decisiveEvidenceFor_whenAlreadyOutsideOnACoarseFix_thenAgreesInsteadOfRefusing() {
        // The same fix with nothing committed. It is not a transition, but it is a decisive
        // observation and the record should say so: `polygon.unchanged` is the population a margin
        // is calibrated against, and reporting it as `accuracy_too_low` put decisive fixes in the
        // refusal bucket and inflated it.
        val sample = sample(latitude = 0.0, longitude = 0.011, accuracyMeters = 120.0)

        val result = evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE)

        result.evidence shouldBeEqualTo PolygonEvidence.AMBIGUOUS
        result.agreedWithCommittedState shouldBeEqualTo true
        result.undecidedReason shouldBeEqualTo null
    }

    @Test
    fun decisiveEvidenceFor_whenDepartingOnACoarseFixNearTheRing_thenStillRefuses() {
        // The other half: clearance is read before accuracy, not instead of it. This fix sits ~56 m
        // outside the ring with 120 m of uncertainty, so it does not clear the margin and the
        // departure is still refused. It falls through to the ceiling, which is why the reason is
        // the accuracy one rather than the margin one — the fix genuinely cannot resolve 56 m, and
        // both refusals are true of it.
        val sample = sample(latitude = 0.0, longitude = 0.0015, accuracyMeters = 120.0)

        val result = evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.INSIDE)

        result.evidence shouldBeEqualTo PolygonEvidence.AMBIGUOUS
        result.undecidedReason shouldBeEqualTo PolygonUndecidedReason.ACCURACY_TOO_LOW
    }

    @Test
    fun decisiveEvidenceFor_whenArrivingOnACoarseFix_thenStillRefuses() {
        // The arrival side is deliberately untouched by this change. The ceiling still governs it,
        // still at the same value, so nothing about which arrivals are reported moves here.
        val sample = sample(latitude = 0.0, longitude = 0.0, accuracyMeters = 60.0)

        val result = evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE)

        result.evidence shouldBeEqualTo PolygonEvidence.AMBIGUOUS
        result.undecidedReason shouldBeEqualTo PolygonUndecidedReason.ACCURACY_TOO_LOW
    }

    @Test
    fun decisiveEvidenceFor_whenAccuracyIsTooLow_thenRemainsAmbiguous() {
        val sample = sample(latitude = 0.0, longitude = 0.0, accuracyMeters = 60.0)

        evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE).evidence shouldBeEqualTo
            PolygonEvidence.AMBIGUOUS
    }

    /**
     * A fence wide enough that a 200 m accuracy disk still sits comfortably inside it, so the
     * accuracy ceiling is the only thing that can produce AMBIGUOUS at the centre. Roughly 1.1 km
     * to a side. The small fence above cannot separate the two: any fix coarse enough to test the
     * ceiling already swamps it.
     */
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

    /**
     * Exiting inflates the fix's accuracy a little and clamps the result, and neither the inflation
     * nor the clamp was reachable from the existing test, which inflates an accuracy of zero.
     *
     * Effective accuracy is `max(acc, min(acc + inflation, clamp))`, so the clamp can only ever
     * shave the inflation and never drop below the fix's own accuracy. Its entire influence is
     * therefore two metres wide, which is why these fixtures sit a metre either side of it.
     */
}
