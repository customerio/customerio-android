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
