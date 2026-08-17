package io.customer.geofence.polygon

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBeGreaterOrEqualTo
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.amshove.kluent.shouldThrow
import org.junit.Test

class PolygonEnclosingCircleTest {
    @Test
    fun calculate_givenSmallPolygon_expectOneKilometerCoarseProximityRing() {
        val geometry = PolygonGeometry.from(
            listOf(point(37.0, -122.0), point(37.0, -121.9999), point(37.0001, -122.0))
        )

        PolygonEnclosingCircle().calculate(geometry).radiusMeters shouldBeGreaterOrEqualTo 1_000f
    }

    @Test
    fun calculate_givenConcavePolygon_expectAllVerticesAndSampledEdgesEnclosed() {
        val geometry = PolygonGeometry.from(
            listOf(
                point(37.0, -122.0),
                point(37.0, -121.99),
                point(37.004, -121.994),
                point(37.01, -121.99),
                point(37.01, -122.0)
            )
        )

        val circle = PolygonEnclosingCircle().calculate(geometry)

        geometry.vertices.forEachIndexed { index, start ->
            val end = geometry.vertices[(index + 1) % geometry.vertices.size]
            (0..20).forEach { step ->
                val fraction = step / 20.0
                val sample = point(
                    start.latitude + (end.latitude - start.latitude) * fraction,
                    start.longitude + (end.longitude - start.longitude) * fraction
                )
                distanceMeters(circle.center, sample) shouldBeLessOrEqualTo circle.radiusMeters.toDouble() + 1.0
            }
        }
    }

    @Test
    fun calculate_givenHighLatitudePolygon_expectVerticesEnclosed() {
        val geometry = PolygonGeometry.from(
            listOf(
                point(79.99, 10.0),
                point(79.99, 10.04),
                point(80.01, 10.04),
                point(80.01, 10.0)
            )
        )

        val circle = PolygonEnclosingCircle().calculate(geometry)

        geometry.vertices.forEach { vertex ->
            distanceMeters(circle.center, vertex) shouldBeLessOrEqualTo circle.radiusMeters.toDouble() + 2.0
        }
    }

    @Test
    fun calculate_givenLargeLatitudeSixtyPolygon_expectGeodesicProjectionErrorStillEnclosed() {
        val geometry = PolygonGeometry.from(
            listOf(
                point(59.886424154084004, -1.762259586786068),
                point(60.04057379427149, -1.0816102014714348),
                point(60.23153152973062, -0.2785170353886745),
                point(60.241775585773155, -0.5624038544322282)
            )
        )

        val circle = PolygonEnclosingCircle().calculate(geometry)

        geometry.vertices.forEach { vertex ->
            distanceMeters(circle.center, vertex) shouldBeLessOrEqualTo circle.radiusMeters.toDouble()
        }
    }

    @Test
    fun calculate_givenPolygonWiderThanSupportedMonitoringArea_expectRejected() {
        val geometry = PolygonGeometry.from(
            listOf(point(0.0, 0.0), point(0.0, 3.0), point(1.0, 1.5))
        )

        invoking { PolygonEnclosingCircle().calculate(geometry) } shouldThrow IllegalArgumentException::class
    }

    private fun distanceMeters(first: PolygonCoordinate, second: PolygonCoordinate): Double {
        val firstLatitude = Math.toRadians(first.latitude)
        val secondLatitude = Math.toRadians(second.latitude)
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(firstLatitude) * cos(secondLatitude) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2.0 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1.0 - a))
    }

    private fun point(latitude: Double, longitude: Double) = PolygonCoordinate(latitude, longitude)

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
