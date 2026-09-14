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
    fun evidenceFor_whenAccuracyDiskIsInside_thenReturnsEnterEvidence() {
        val sample = sample(latitude = 0.0, longitude = 0.0, accuracyMeters = 10.0)

        evaluator.evidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE) shouldBeEqualTo
            PolygonEvidence.ENTER
    }

    @Test
    fun evidenceFor_whenAccuracyDiskStraddlesBoundary_thenReturnsAmbiguousEvidence() {
        val sample = sample(latitude = 0.00099, longitude = 0.00099, accuracyMeters = 20.0)

        evaluator.evidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE) shouldBeEqualTo
            PolygonEvidence.AMBIGUOUS
    }

    @Test
    fun evidenceFor_whenAccuracyDiskIsOutside_thenReturnsExitEvidence() {
        val sample = sample(latitude = 0.0, longitude = 0.002, accuracyMeters = 10.0)

        evaluator.evidenceFor(geometry, sample, PolygonCommittedState.INSIDE) shouldBeEqualTo
            PolygonEvidence.EXIT
    }

    @Test
    fun evidenceFor_whenCenterIsOnBoundary_thenReturnsAmbiguousEvidence() {
        val sample = sample(latitude = 0.0, longitude = 0.001, accuracyMeters = 0.0)

        evaluator.evidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE) shouldBeEqualTo
            PolygonEvidence.AMBIGUOUS
    }

    @Test
    fun evidenceFor_whenExitAccuracyInflationTouchesPolygon_thenDefersExit() {
        val sample = sample(latitude = 0.0, longitude = 0.00101, accuracyMeters = 0.0)

        evaluator.evidenceFor(geometry, sample, PolygonCommittedState.INSIDE) shouldBeEqualTo
            PolygonEvidence.AMBIGUOUS
    }

    @Test
    fun evidenceFor_whenAccuracyIsGrosslyInaccurate_thenReturnsAmbiguousEvidence() {
        val sample = sample(latitude = 0.0, longitude = 0.002, accuracyMeters = 250.0)

        evaluator.evidenceFor(geometry, sample, PolygonCommittedState.INSIDE) shouldBeEqualTo
            PolygonEvidence.AMBIGUOUS
    }

    @Test
    fun evidenceFor_whenAccuracyDiskContainsSmallPolygon_thenDefersExit() {
        val smallGeometry = PolygonGeometry.from(
            listOf(
                point(-0.0001, -0.0001),
                point(-0.0001, 0.0001),
                point(0.0001, 0.0001),
                point(0.0001, -0.0001)
            )
        )
        val sample = sample(latitude = 0.0, longitude = 0.0009, accuracyMeters = 150.0)

        evaluator.evidenceFor(smallGeometry, sample, PolygonCommittedState.INSIDE) shouldBeEqualTo
            PolygonEvidence.AMBIGUOUS
    }

    @Test
    fun decisiveEvidenceFor_whenAccuracyCircleAndMarginAreInside_thenReturnsEnter() {
        val sample = sample(latitude = 0.0, longitude = 0.0, accuracyMeters = 10.0)

        evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE) shouldBeEqualTo
            PolygonEvidence.ENTER
    }

    @Test
    fun decisiveEvidenceFor_whenFixIsNearBoundary_thenRemainsAmbiguous() {
        val sample = sample(latitude = 0.0, longitude = 0.0009, accuracyMeters = 5.0)

        evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE) shouldBeEqualTo
            PolygonEvidence.AMBIGUOUS
    }

    @Test
    fun decisiveEvidenceFor_whenAccuracyIsTooLow_thenRemainsAmbiguous() {
        val sample = sample(latitude = 0.0, longitude = 0.0, accuracyMeters = 60.0)

        evaluator.decisiveEvidenceFor(geometry, sample, PolygonCommittedState.OUTSIDE) shouldBeEqualTo
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

    @Test
    fun evidenceFor_whenAccuracyIsJustInsideTheCeiling_thenStillDecides() {
        val sample = sample(latitude = 0.0, longitude = 0.0, accuracyMeters = 199.0)

        evaluator.evidenceFor(wideGeometry, sample, PolygonCommittedState.OUTSIDE) shouldBeEqualTo
            PolygonEvidence.ENTER
    }

    @Test
    fun evidenceFor_whenAccuracyIsJustOverTheCeiling_thenReturnsAmbiguousOnPositionItCouldJudge() {
        // Same centre, 2 m coarser. Only the ceiling separates this from the case above, so the
        // pair fails if the ceiling moves rather than passing on the geometry either way.
        val sample = sample(latitude = 0.0, longitude = 0.0, accuracyMeters = 201.0)

        evaluator.evidenceFor(wideGeometry, sample, PolygonCommittedState.OUTSIDE) shouldBeEqualTo
            PolygonEvidence.AMBIGUOUS
    }

    @Test
    fun evidenceFor_whenMostOfTheAccuracyDiskIsInside_thenEntersOnPartialConfidence() {
        // Centre sits ~55 m inside the east edge with a 100 m disk, so roughly two thirds of the
        // perimeter samples fall inside. Pins that entering does NOT require the whole disk.
        val sample = sample(latitude = 0.0, longitude = 0.0095, accuracyMeters = 100.0)

        evaluator.evidenceFor(wideGeometry, sample, PolygonCommittedState.OUTSIDE) shouldBeEqualTo
            PolygonEvidence.ENTER
    }

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
