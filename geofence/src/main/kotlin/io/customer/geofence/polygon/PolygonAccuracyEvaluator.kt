package io.customer.geofence.polygon

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal data class PolygonLocationSample(
    val coordinate: PolygonCoordinate,
    val horizontalAccuracyMeters: Double
) {
    init {
        require(horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters >= 0.0) {
            "horizontal accuracy must be finite and non-negative"
        }
    }
}

internal enum class PolygonCommittedState {
    OUTSIDE,
    INSIDE
}

internal enum class PolygonEvidence {
    ENTER,
    EXIT,
    AMBIGUOUS
}

internal data class PolygonEvaluatorConfig(
    val perimeterSampleCount: Int = 16,
    val enterConfidenceThreshold: Double = 0.4,
    val exitAccuracyInflationMeters: Double = 2.0,
    val maximumExitAccuracyMeters: Double = 50.0,
    val maximumAcceptedAccuracyMeters: Double = 200.0
) {
    init {
        require(perimeterSampleCount in 8..64) { "perimeter sample count is outside safe bounds" }
        require(enterConfidenceThreshold in 0.25..0.75) { "enter confidence is outside safe bounds" }
        require(exitAccuracyInflationMeters in 0.0..10.0) { "exit accuracy inflation is outside safe bounds" }
        require(maximumExitAccuracyMeters in 10.0..100.0) { "maximum exit accuracy is outside safe bounds" }
        require(maximumAcceptedAccuracyMeters in 50.0..500.0) { "accepted accuracy is outside safe bounds" }
    }
}

/** Classifies a location fix without mutating committed polygon state. */
internal class PolygonAccuracyEvaluator(
    private val config: PolygonEvaluatorConfig = PolygonEvaluatorConfig()
) {
    fun evidenceFor(
        geometry: PolygonGeometry,
        sample: PolygonLocationSample,
        committedState: PolygonCommittedState
    ): PolygonEvidence {
        if (sample.horizontalAccuracyMeters > config.maximumAcceptedAccuracyMeters) {
            return PolygonEvidence.AMBIGUOUS
        }
        val centerRelation = geometry.relationTo(sample.coordinate)
        if (centerRelation == PolygonPointRelation.BOUNDARY) return PolygonEvidence.AMBIGUOUS

        val accuracy = when (committedState) {
            PolygonCommittedState.OUTSIDE -> sample.horizontalAccuracyMeters
            PolygonCommittedState.INSIDE -> max(
                sample.horizontalAccuracyMeters,
                min(
                    sample.horizontalAccuracyMeters + config.exitAccuracyInflationMeters,
                    config.maximumExitAccuracyMeters
                )
            )
        }
        val perimeterRelations = perimeterSamples(sample.coordinate, accuracy)
            .map(geometry::relationTo)
        val insideCount = perimeterRelations.count { it == PolygonPointRelation.INSIDE }
        val confidence = insideCount.toDouble() / config.perimeterSampleCount

        return when {
            centerRelation == PolygonPointRelation.INSIDE &&
                confidence >= config.enterConfidenceThreshold -> PolygonEvidence.ENTER
            centerRelation == PolygonPointRelation.OUTSIDE &&
                geometry.boundaryDistanceMeters(sample.coordinate) > accuracy ->
                PolygonEvidence.EXIT
            else -> PolygonEvidence.AMBIGUOUS
        }
    }

    private fun perimeterSamples(
        center: PolygonCoordinate,
        radiusMeters: Double
    ): List<PolygonCoordinate> = List(config.perimeterSampleCount) { index ->
        val bearing = 2.0 * PI * index / config.perimeterSampleCount
        destination(center, bearing, radiusMeters)
    }

    private fun destination(
        origin: PolygonCoordinate,
        bearingRadians: Double,
        distanceMeters: Double
    ): PolygonCoordinate {
        if (distanceMeters == 0.0) return origin

        val latitude = Math.toRadians(origin.latitude)
        val longitude = Math.toRadians(origin.longitude)
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val destinationLatitude = asin(
            sin(latitude) * cos(angularDistance) +
                cos(latitude) * sin(angularDistance) * cos(bearingRadians)
        )
        val destinationLongitude = longitude + atan2(
            sin(bearingRadians) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(destinationLatitude)
        )

        return PolygonCoordinate(
            latitude = Math.toDegrees(destinationLatitude),
            longitude = normalizeLongitude(Math.toDegrees(destinationLongitude))
        )
    }

    private fun normalizeLongitude(longitude: Double): Double =
        ((longitude + 540.0) % 360.0) - 180.0

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
