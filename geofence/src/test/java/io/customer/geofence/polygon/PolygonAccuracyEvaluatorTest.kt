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
