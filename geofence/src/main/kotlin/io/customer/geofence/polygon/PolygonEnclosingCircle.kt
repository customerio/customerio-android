package io.customer.geofence.polygon

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal data class PolygonTriggerCircle(
    val center: PolygonCoordinate,
    val radiusMeters: Float
)

internal class PolygonEnclosingCircle {
    fun calculate(geometry: PolygonGeometry): PolygonTriggerCircle {
        val projection = Projection(geometry.vertices)
        val points = geometry.vertices
            .map(projection::project)
            .shuffled(Random(DETERMINISTIC_SHUFFLE_SEED))
        var circle: Circle? = null

        points.forEachIndexed { firstIndex, first ->
            if (circle?.contains(first) == true) return@forEachIndexed
            circle = Circle(first, 0.0)
            for (secondIndex in 0 until firstIndex) {
                val second = points[secondIndex]
                if (circle?.contains(second) == true) continue
                circle = diameterCircle(first, second)
                for (thirdIndex in 0 until secondIndex) {
                    val third = points[thirdIndex]
                    if (circle?.contains(third) == true) continue
                    circle = circumcircle(first, second, third)
                        ?: farthestDiameterCircle(first, second, third)
                }
            }
        }

        val enclosing = requireNotNull(circle)
        val center = projection.unproject(enclosing.center)
        // The minimum circle is solved in a local equirectangular projection. Re-measure its
        // vertices on the globe before adding trigger padding: projection error grows with both
        // latitude and polygon size, and can otherwise leave a valid vertex outside the GMS circle.
        // A conservative upper-bound Earth radius avoids underestimating the OS's geodesic circle.
        val geodesicVertexRadius = geometry.vertices.maxOf { center.distanceUpperBoundMeters(it) }
        val paddedRadius = max(
            MINIMUM_TRIGGER_RADIUS_METERS,
            max(enclosing.radius, geodesicVertexRadius) + TRIGGER_PADDING_METERS
        )
        require(paddedRadius <= MAXIMUM_TRIGGER_RADIUS_METERS) {
            "polygon enclosing circle exceeds the maximum supported radius"
        }
        return PolygonTriggerCircle(
            center = center,
            radiusMeters = paddedRadius.toFloat()
        )
    }

    private fun PolygonCoordinate.distanceUpperBoundMeters(other: PolygonCoordinate): Double {
        val firstLatitude = Math.toRadians(latitude)
        val secondLatitude = Math.toRadians(other.latitude)
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(other.longitude - longitude)
        val haversine = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
            cos(firstLatitude) * cos(secondLatitude) *
            sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
        val clampedHaversine = haversine.coerceIn(0.0, 1.0)
        val centralAngle = 2.0 * atan2(sqrt(clampedHaversine), sqrt(1.0 - clampedHaversine))
        return MAXIMUM_EARTH_RADIUS_METERS * centralAngle
    }

    private fun diameterCircle(first: Point, second: Point): Circle {
        val center = Point((first.x + second.x) / 2.0, (first.y + second.y) / 2.0)
        return Circle(center, center.distanceTo(first))
    }

    private fun circumcircle(first: Point, second: Point, third: Point): Circle? {
        val denominator = 2.0 * (
            first.x * (second.y - third.y) +
                second.x * (third.y - first.y) +
                third.x * (first.y - second.y)
            )
        if (abs(denominator) <= CIRCLE_EPSILON) return null

        val firstSquared = first.x * first.x + first.y * first.y
        val secondSquared = second.x * second.x + second.y * second.y
        val thirdSquared = third.x * third.x + third.y * third.y
        val center = Point(
            x = (
                firstSquared * (second.y - third.y) +
                    secondSquared * (third.y - first.y) +
                    thirdSquared * (first.y - second.y)
                ) / denominator,
            y = (
                firstSquared * (third.x - second.x) +
                    secondSquared * (first.x - third.x) +
                    thirdSquared * (second.x - first.x)
                ) / denominator
        )
        return Circle(center, center.distanceTo(first))
    }

    private fun farthestDiameterCircle(first: Point, second: Point, third: Point): Circle =
        listOf(first to second, first to third, second to third)
            .maxBy { (start, end) -> start.distanceSquaredTo(end) }
            .let { (start, end) -> diameterCircle(start, end) }

    private data class Point(val x: Double, val y: Double) {
        fun distanceSquaredTo(other: Point): Double {
            val deltaX = x - other.x
            val deltaY = y - other.y
            return deltaX * deltaX + deltaY * deltaY
        }

        fun distanceTo(other: Point): Double = sqrt(distanceSquaredTo(other))
    }

    private data class Circle(val center: Point, val radius: Double) {
        fun contains(point: Point): Boolean =
            center.distanceTo(point) <= radius + CIRCLE_EPSILON
    }

    private class Projection(vertices: List<PolygonCoordinate>) {
        private val originLatitude = vertices.map(PolygonCoordinate::latitude).average()
        private val originLongitude = vertices.map(PolygonCoordinate::longitude).average()
        private val longitudeScale = cos(Math.toRadians(originLatitude))

        init {
            require(abs(longitudeScale) > MINIMUM_LONGITUDE_SCALE) {
                "polygons at the geographic poles are unsupported"
            }
        }

        fun project(coordinate: PolygonCoordinate): Point = Point(
            x = EARTH_RADIUS_METERS * Math.toRadians(coordinate.longitude - originLongitude) * longitudeScale,
            y = EARTH_RADIUS_METERS * Math.toRadians(coordinate.latitude - originLatitude)
        )

        fun unproject(point: Point): PolygonCoordinate = PolygonCoordinate(
            latitude = originLatitude + Math.toDegrees(point.y / EARTH_RADIUS_METERS),
            longitude = normalizeLongitude(
                originLongitude + Math.toDegrees(point.x / (EARTH_RADIUS_METERS * longitudeScale))
            )
        )

        private fun normalizeLongitude(longitude: Double): Double =
            ((longitude + 540.0) % 360.0) - 180.0
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val MAXIMUM_EARTH_RADIUS_METERS = 6_400_000.0

        // Android background geofence callbacks can arrive minutes late. A 1 km proximity ring,
        // matching mature mobile geofence engines, wakes fine evaluation materially earlier than
        // a GPS-noise-only buffer. It is still a coarse trigger, never the business boundary.
        const val TRIGGER_PADDING_METERS = 1_000.0
        const val MINIMUM_TRIGGER_RADIUS_METERS = 1_000.0
        const val MAXIMUM_TRIGGER_RADIUS_METERS = 100_000.0
        const val CIRCLE_EPSILON = 1e-6
        const val MINIMUM_LONGITUDE_SCALE = 1e-6
        const val DETERMINISTIC_SHUFFLE_SEED = 0
    }
}
